"""HEL-95 contract harness — the SAME tests run against two targets:

  hermetic (default)   in-process FastAPI TestClient, local parquet fixture,
                       MinIO-touching seams patched. Captures today's Python
                       behavior as the executable contract.
  remote               QUERYSKIFF_CONTRACT_URL=<base url> — plain HTTP against
                       a running deployment (the Python pod today; the Kotlin/
                       Quarkus canary during migration). This is the parity
                       gate: the port passes when this suite is green remotely.

Env knobs are pinned BEFORE the app imports (config is frozen at import time)
so limit-injection and truncation contracts are provable with a small fixture.
"""
from __future__ import annotations

import os
import sys
import time
from pathlib import Path

import pytest

REPO_BACKEND = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_BACKEND))

REMOTE = os.getenv("QUERYSKIFF_CONTRACT_URL", "").rstrip("/")

FIXTURE_BUCKET = "contractbkt"
FIXTURE_KEY = "fixture/rows.parquet"
FIXTURE_KEY2 = "fixture/prices.parquet"   # HEL-112: join partner (shares `symbol`)
FIXTURE_ROWS = 200          # > DEFAULT_LIMIT and > MAX_RESULT_ROWS below
DEFAULT_LIMIT = 50
MAX_RESULT_ROWS = 100

if not REMOTE:
    # pin the contract-relevant knobs before queryskiff.config freezes them
    os.environ["QUERYSKIFF_ALLOWED_BUCKETS"] = FIXTURE_BUCKET
    os.environ["QUERYSKIFF_DEFAULT_LIMIT"] = str(DEFAULT_LIMIT)
    os.environ["QUERYSKIFF_MAX_RESULT_ROWS"] = str(MAX_RESULT_ROWS)
    os.environ["QUERYSKIFF_TIMEOUT_SECONDS"] = "20"
    os.environ.setdefault("QUERYSKIFF_BASE_PATH", "/queryskiff")


class Client:
    """Uniform GET/POST/DELETE over TestClient or requests; returns
    (status_code, parsed_json_or_None)."""

    def __init__(self, base: str, tc=None):
        self.base = base
        self._tc = tc

    def _do(self, method: str, path: str, json_body=None):
        url = f"{self.base}{path}"
        if self._tc is not None:
            r = getattr(self._tc, method)(url, **({"json": json_body} if json_body is not None else {}))
            try:
                return r.status_code, r.json()
            except Exception:  # noqa: BLE001
                return r.status_code, None
        import requests
        r = requests.request(method.upper(), url, json=json_body, timeout=30)
        try:
            return r.status_code, r.json()
        except Exception:  # noqa: BLE001
            return r.status_code, None

    def get(self, path):
        return self._do("get", path)

    def post(self, path, json_body):
        return self._do("post", path, json_body)

    def delete(self, path):
        return self._do("delete", path)

    def wait_done(self, qid: str, timeout: float = 30.0) -> dict:
        deadline = time.time() + timeout
        while time.time() < deadline:
            code, body = self.get(f"/api/queries/{qid}")
            assert code == 200, body
            if body["status"] in ("done", "error", "cancelled"):
                return body
            time.sleep(0.1)
        raise AssertionError(f"query {qid} did not settle within {timeout}s")


@pytest.fixture(scope="session")
def fixture_dataset_id() -> str:
    """Hermetic: the known fixture id. Remote: discovered from the live
    listing (first file dataset) — content-agnostic contracts only."""
    if REMOTE:
        import requests
        r = requests.get(f"{REMOTE}/api/datasets", timeout=30)
        r.raise_for_status()
        files = [d for d in r.json()["datasets"] if d.get("kind") == "file"]
        if not files:
            pytest.skip("remote target has no file datasets to exercise")
        return files[0]["dataset_id"]
    from queryskiff.datasets import encode_id
    return encode_id(FIXTURE_BUCKET, FIXTURE_KEY)


@pytest.fixture(scope="session")
def client(tmp_path_factory) -> Client:
    if REMOTE:
        base = REMOTE if REMOTE.endswith("/queryskiff") else REMOTE + "/queryskiff"
        return Client(base)

    # ── hermetic target ──────────────────────────────────────────────────────
    import duckdb

    fx_dir = tmp_path_factory.mktemp("contract-fixture")
    fx = fx_dir / "rows.parquet"
    duckdb.connect().execute(
        f"COPY (SELECT i AS id, 'sym' || (i % 7) AS symbol, i * 0.5 AS score "
        f"FROM range({FIXTURE_ROWS}) t(i)) TO '{fx}' (FORMAT parquet)")
    # HEL-112 join partner: one price row per symbol (7 rows), joinable on `symbol`
    fx2 = fx_dir / "prices.parquet"
    duckdb.connect().execute(
        f"COPY (SELECT 'sym' || i AS symbol, 100.0 + i AS price "
        f"FROM range(7) t(i)) TO '{fx2}' (FORMAT parquet)")

    from queryskiff import datasets, engine  # imports AFTER env pinning
    from queryskiff.datasets import Dataset, encode_id

    fid = encode_id(FIXTURE_BUCKET, FIXTURE_KEY)

    # MinIO seams -> local fixture. resolve_id/validate/engine stay REAL. The
    # shapes here MIRROR the real datasets.py (HEL-90 browser-safe contract): a
    # logical label only, no bucket / key / etag.
    datasets.list_datasets = lambda: [{
        "dataset_id": fid, "name": datasets.display_label(FIXTURE_KEY, False),
        "kind": "file", "size": fx.stat().st_size,
        "modified": "2026-07-27T00:00:00+00:00",
    }]
    datasets.object_metadata = lambda ds: {
        "kind": "file", "name": ds.label, "size": fx.stat().st_size,
        "modified": "2026-07-27T00:00:00+00:00",
        "content_type": "application/octet-stream",
    }
    real_new_connection = engine._new_connection

    _local_files = {FIXTURE_KEY: fx, FIXTURE_KEY2: fx2}

    def _local_connection(entries: list[tuple[Dataset, str]]):
        # mirrors the real signature (HEL-112): one view per (dataset, alias)
        conn = duckdb.connect(database=":memory:")
        for ds, alias in entries:
            conn.read_parquet(str(_local_files[ds.key])).create_view(alias, replace=True)
        return conn

    engine._new_connection = _local_connection  # noqa: SLF001 — test seam
    _ = real_new_connection  # kept for clarity: the real one needs MinIO/httpfs

    from fastapi.testclient import TestClient

    from queryskiff.app import app
    return Client("/queryskiff", TestClient(app))
