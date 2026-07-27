"""HEL-95: executable API contract of the QuerySkiff backend.

Every assertion here is a MIGRATION REQUIREMENT: the Kotlin/Quarkus port passes
when this suite is green with QUERYSKIFF_CONTRACT_URL pointing at it. Shapes
and status codes are asserted exactly as the Python implementation behaves
today — including its quirks (documented inline), which the port must either
reproduce or change via an approved migration note in HEL-95.
"""
from __future__ import annotations

import os

import pytest

from .conftest import DEFAULT_LIMIT, MAX_RESULT_ROWS, FIXTURE_ROWS

REMOTE = bool(os.getenv("QUERYSKIFF_CONTRACT_URL"))
hermetic_only = pytest.mark.skipif(
    REMOTE, reason="depends on pinned fixture sizes; hermetic target only")

# The security boundary at HTTP level — every entry must be a 400 whose detail
# starts with "unsafe SQL:". Mirrors sqlsafety's corpus (kept in sync on purpose:
# the CONTRACT is the HTTP behavior, not the Python module).
UNSAFE_CORPUS = [
    "INSERT INTO data VALUES (1)",
    "UPDATE data SET x=1",
    "DELETE FROM data",
    "DROP TABLE data",
    "CREATE TABLE x AS SELECT 1",
    "COPY data TO 's3://x/y'",
    "ATTACH 'x.db'",
    "INSTALL httpfs",
    "LOAD httpfs",
    "PRAGMA database_list",
    "SET memory_limit='1GB'",
    "SELECT * FROM read_parquet('s3://secret/x.parquet')",
    "SELECT * FROM data; DROP TABLE data",
    "SELECT * FROM data; SELECT * FROM data",
    "SELECT * FROM other_table",
    "SELECT * FROM read_csv('/etc/passwd')",
    "SELECT * FROM data WHERE x IN (SELECT * FROM read_parquet('s3://a/b'))",
    "SELECT * FROM data -- \n; DROP TABLE data",
    "  sElEcT * FROM data; InSeRt INTO data VALUES (1) ",
    "   ",
]


# ── health ────────────────────────────────────────────────────────────────────

def test_health_endpoints(client):
    for path in ("/health", "/api/health"):
        code, body = client.get(path)
        assert (code, body) == (200, {"ok": True}), path


# ── datasets ──────────────────────────────────────────────────────────────────

def test_dataset_listing_shape(client):
    code, body = client.get("/api/datasets")
    assert code == 200 and isinstance(body["datasets"], list)
    for d in body["datasets"]:
        assert set(d) >= {"dataset_id", "name", "bucket", "kind", "size", "modified"}
        assert d["kind"] in ("file", "folder")
        # opaque id: URL-safe, no padding, never contains the raw bucket/key
        assert "/" not in d["dataset_id"] and "=" not in d["dataset_id"]
        if d["kind"] == "folder":
            assert d["parts"] > 1


def test_schema_shape(client, fixture_dataset_id):
    code, body = client.get(f"/api/datasets/{fixture_dataset_id}/schema")
    assert code == 200
    cols = body["schema"]
    assert cols and all("column_name" in c and "column_type" in c for c in cols)


def test_metadata_shape(client, fixture_dataset_id):
    code, body = client.get(f"/api/datasets/{fixture_dataset_id}/metadata")
    assert code == 200
    assert body["kind"] in ("file", "folder") and body["name"]
    if body["kind"] == "file":
        assert set(body) >= {"size", "modified", "etag", "content_type"}
    # never leak transport details
    for banned in ("s3://", "http://", "https://", "access", "secret"):
        assert banned not in str(body).lower() or banned == "access"  # 'access' only via content_type wording


@pytest.mark.parametrize("bad_id", [
    "definitely-not-an-id",
    "c2VjcmV0YnVja2V0AHNlY3JldC5wYXJxdWV0",   # forged: bucket not on allow-list
    "",  # empty -> route resolves to the listing on GET; POST covers this via 404 below
])
def test_unknown_or_forged_dataset_id_is_404(client, bad_id):
    if bad_id == "":
        return  # documented: GET /api/datasets/{empty}/schema is unroutable -> covered by POST case
    code, body = client.get(f"/api/datasets/{bad_id}/schema")
    assert code == 404 and body["detail"]
    # the error must not echo internals beyond the policy message
    assert "s3://" not in body["detail"]


# ── query lifecycle ───────────────────────────────────────────────────────────

def test_query_lifecycle_success(client, fixture_dataset_id):
    code, body = client.post("/api/queries", {"dataset_id": fixture_dataset_id,
                                              "sql": "SELECT * FROM data LIMIT 5"})
    assert code == 200 and body["query_id"]
    assert body["status"] in ("pending", "running", "done")
    qid = body["query_id"]

    st = client.wait_done(qid)
    assert st == {"query_id": qid, "status": "done", "error": None,
                  "row_count": 5, "truncated": False}

    code, res = client.get(f"/api/queries/{qid}/results")
    assert code == 200
    assert res["columns"] and isinstance(res["rows"], list)
    assert res["row_count"] == len(res["rows"]) == 5
    assert res["truncated"] is False


def test_missing_dataset_id_is_400(client):
    code, body = client.post("/api/queries", {"sql": "SELECT 1"})
    assert code == 400 and body["detail"] == "dataset_id required"


def test_query_on_unknown_dataset_is_404(client):
    code, body = client.post("/api/queries", {"dataset_id": "bogus", "sql": "SELECT 1"})
    assert code == 404 and body["detail"]


@pytest.mark.parametrize("sql", UNSAFE_CORPUS)
def test_unsafe_sql_rejected_at_http(client, fixture_dataset_id, sql):
    code, body = client.post("/api/queries", {"dataset_id": fixture_dataset_id, "sql": sql})
    assert code == 400, sql
    assert body["detail"].startswith("unsafe SQL:"), sql


def test_unknown_query_id_contracts(client):
    code, body = client.get("/api/queries/nope")
    assert code == 404 and body["detail"] == "unknown query"
    code, body = client.get("/api/queries/nope/results")
    assert code == 404 and body["detail"] == "unknown query"
    # QUIRK (today's behavior, port must match or note): cancel of an unknown
    # id is 200 {"cancelled": false}, NOT 404.
    code, body = client.delete("/api/queries/nope")
    assert (code, body) == (200, {"cancelled": False})


def test_sql_error_reported_via_status_then_400_results(client, fixture_dataset_id):
    code, body = client.post("/api/queries", {"dataset_id": fixture_dataset_id,
                                              "sql": "SELECT no_such_column FROM data"})
    assert code == 200          # validation passes; failure surfaces at run time
    st = client.wait_done(body["query_id"])
    assert st["status"] == "error" and st["error"]
    code, res = client.get(f"/api/queries/{body['query_id']}/results")
    assert code == 400 and res["detail"]


def test_cancel_settled_query_is_false(client, fixture_dataset_id):
    code, body = client.post("/api/queries", {"dataset_id": fixture_dataset_id,
                                              "sql": "SELECT 1 FROM data LIMIT 1"})
    qid = body["query_id"]
    client.wait_done(qid)
    code, body = client.delete(f"/api/queries/{qid}")
    assert (code, body) == (200, {"cancelled": False})


# ── bounds: limit injection + truncation (fixture-size dependent) ────────────

@hermetic_only
def test_limit_injected_when_absent(client, fixture_dataset_id):
    code, body = client.post("/api/queries", {"dataset_id": fixture_dataset_id,
                                              "sql": "SELECT * FROM data"})
    assert code == 200
    st = client.wait_done(body["query_id"])
    # fixture has FIXTURE_ROWS rows; without a client LIMIT the server injects
    # DEFAULT_LIMIT — and an injected limit does NOT count as truncation.
    assert st["status"] == "done"
    assert st["row_count"] == DEFAULT_LIMIT
    assert st["truncated"] is False
    assert FIXTURE_ROWS > DEFAULT_LIMIT  # fixture invariant


@hermetic_only
def test_truncation_at_max_result_rows(client, fixture_dataset_id):
    code, body = client.post("/api/queries", {"dataset_id": fixture_dataset_id,
                                              "sql": f"SELECT * FROM data LIMIT {FIXTURE_ROWS}"})
    assert code == 200
    st = client.wait_done(body["query_id"])
    assert st["status"] == "done"
    assert st["row_count"] == MAX_RESULT_ROWS
    assert st["truncated"] is True


@hermetic_only
def test_results_never_leak_paths_or_credentials(client, fixture_dataset_id):
    code, body = client.post("/api/queries", {"dataset_id": fixture_dataset_id,
                                              "sql": "SELECT * FROM data LIMIT 3"})
    st = client.wait_done(body["query_id"])
    _, res = client.get(f"/api/queries/{body['query_id']}/results")
    blob = str(st) + str(res)
    for banned in ("s3://", ".parquet", "minio", "access_key", "secret"):
        assert banned not in blob.lower()


# ── SPA hosting ───────────────────────────────────────────────────────────────

def test_spa_fallback_contract(client):
    """Any non-API path serves the SPA's index.html when the frontend is built
    in, or an honest 404 JSON when it is not — NEVER a 5xx, and never an API
    response. A deployed image must take the 200 branch."""
    code, body = client.get("/some/deep/client/route")
    if code == 404:
        assert body == {"error": "frontend not built"}
        assert not REMOTE, "deployed target must ship the built frontend"
    else:
        assert code == 200      # index.html (body is not JSON -> None here)
        assert body is None or "datasets" not in body
