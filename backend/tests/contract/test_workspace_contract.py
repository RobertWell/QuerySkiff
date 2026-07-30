"""HEL-112: workspace (multi-dataset join) HTTP contract.

Same dual-target rules as test_api_contract.py: hermetic today, and every
assertion is a migration requirement for the HEL-95 Kotlin/Quarkus port.
"""
from __future__ import annotations

import os

import pytest

from .conftest import FIXTURE_BUCKET, FIXTURE_KEY, FIXTURE_KEY2

REMOTE = bool(os.getenv("QUERYSKIFF_CONTRACT_URL"))
hermetic_only = pytest.mark.skipif(
    REMOTE, reason="needs the pinned two-fixture pair; hermetic target only")


def _ids():
    from queryskiff.datasets import encode_id
    return (encode_id(FIXTURE_BUCKET, FIXTURE_KEY),
            encode_id(FIXTURE_BUCKET, FIXTURE_KEY2))


@hermetic_only
def test_two_dataset_join_with_ordinary_sql(client):
    rid, pid = _ids()
    code, body = client.post("/api/queries", {
        "datasets": [{"dataset_id": rid, "alias": "rows"},
                     {"dataset_id": pid, "alias": "prices"}],
        "sql": "SELECT rows.symbol, count(*) n, max(prices.price) p "
               "FROM rows JOIN prices ON rows.symbol = prices.symbol "
               "GROUP BY rows.symbol ORDER BY rows.symbol LIMIT 10",
    })
    assert code == 200, body
    done = client.wait_done(body["query_id"])
    assert done["status"] == "done", done
    code, res = client.get(f"/api/queries/{body['query_id']}/results")
    assert code == 200
    assert res["columns"] == ["symbol", "n", "p"]
    assert res["row_count"] == 7                     # 7 symbols in the fixtures
    assert all(len(r) == 3 for r in res["rows"])


@hermetic_only
def test_unregistered_table_reference_is_400(client):
    rid, _ = _ids()
    code, body = client.post("/api/queries", {
        "datasets": [{"dataset_id": rid, "alias": "rows"}],
        "sql": "SELECT * FROM prices LIMIT 5",
    })
    assert code == 400 and body["detail"].startswith("unsafe SQL:")


@hermetic_only
def test_forged_dataset_id_in_workspace_is_400(client):
    code, body = client.post("/api/queries", {
        "datasets": [{"dataset_id": "Zm9yZ2VkAGJhZC5wYXJxdWV0", "alias": "x"}],
        "sql": "SELECT * FROM x LIMIT 1",
    })
    assert code == 400
    # browser-safe: no bucket/key/path in the error
    assert "s3://" not in (body["detail"] or "")


@hermetic_only
@pytest.mark.parametrize("alias", ["1bad", "SELECT", "has space", "x" * 31, ""])
def test_invalid_alias_is_400(client, alias):
    rid, _ = _ids()
    code, body = client.post("/api/queries", {
        "datasets": [{"dataset_id": rid, "alias": alias}],
        "sql": "SELECT 1",
    })
    assert code == 400


@hermetic_only
def test_duplicate_alias_is_400(client):
    rid, pid = _ids()
    code, body = client.post("/api/queries", {
        "datasets": [{"dataset_id": rid, "alias": "t"},
                     {"dataset_id": pid, "alias": "t"}],
        "sql": "SELECT * FROM t LIMIT 1",
    })
    assert code == 400 and "duplicate alias" in body["detail"]


@hermetic_only
def test_legacy_single_dataset_data_alias_still_works(client):
    rid, _ = _ids()
    code, body = client.post("/api/queries",
                             {"dataset_id": rid, "sql": "SELECT count(*) c FROM data"})
    assert code == 200
    done = client.wait_done(body["query_id"])
    assert done["status"] == "done"


@hermetic_only
def test_workspace_hints_shape_and_starter_sql(client):
    rid, pid = _ids()
    code, body = client.post("/api/workspace/hints", {
        "datasets": [{"dataset_id": rid, "alias": "rows"},
                     {"dataset_id": pid, "alias": "prices"}],
    })
    assert code == 200, body
    hint = next(h for h in body["hints"] if h["column"] == "symbol")
    assert hint["compatible"] is True
    assert {a["alias"] for a in hint["aliases"]} == {"rows", "prices"}
    assert "JOIN prices ON rows.symbol = prices.symbol" in body["starter_sql"]
    assert set(body["schemas"]) == {"rows", "prices"}


@hermetic_only
def test_workspace_query_cancellable(client):
    rid, pid = _ids()
    code, body = client.post("/api/queries", {
        "datasets": [{"dataset_id": rid, "alias": "a"},
                     {"dataset_id": pid, "alias": "b"}],
        "sql": "SELECT count(*) FROM a, b, a a2, b b2, a a3 LIMIT 1",
    })
    assert code == 200
    code, res = client.delete(f"/api/queries/{body['query_id']}")
    assert code == 200
    final = client.wait_done(body["query_id"])
    assert final["status"] in ("cancelled", "done")   # tiny fixture may finish first
