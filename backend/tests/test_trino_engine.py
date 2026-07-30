"""HEL-112/113: Trino engine + registrar units (no live Trino needed)."""
from __future__ import annotations

import pytest

from queryskiff.datasets import Dataset
from queryskiff.engine_trino import _wrap
from queryskiff.registrar import (RegistrationError, _map_type, _safe_column,
                                  _safe_location, sniff_schema, table_name)


def _ds(i: str) -> Dataset:
    return Dataset(dataset_id=i, bucket="b", key="k.parquet", is_folder=False)


def test_table_name_deterministic_and_identifier_safe():
    a, b = table_name(_ds("id-one")), table_name(_ds("id-two"))
    assert a != b
    assert a == table_name(_ds("id-one"))
    assert a.startswith("t_") and a.replace("_", "").isalnum() and a.islower()


def test_type_map_core_and_decimal_passthrough():
    assert _map_type("BIGINT") == "bigint"
    assert _map_type("VARCHAR") == "varchar"
    assert _map_type("TIMESTAMP") == "timestamp"
    assert _map_type("DECIMAL(10,2)") == "decimal(10,2)"


def test_unknown_type_fails_closed():
    with pytest.raises(RegistrationError):
        _map_type("GEOMETRY")


def test_decimal_must_match_strict_shape():
    assert _map_type("DECIMAL(38,0)") == "decimal(38,0)"
    for bad in ["DECIMAL(38,0) ) WITH (x", "DECIMAL(a,b)", "DECIMAL"]:
        with pytest.raises(RegistrationError):
            _map_type(bad)


@pytest.mark.parametrize("evil", [
    'x" ) WITH (external_location=\'s3://evil/\') --',
    "col; DROP TABLE t",
    "a b",
    '" ',
    "1col",
    "x" * 129,
])
def test_malicious_column_name_rejected(evil):
    # a crafted parquet footer must never reach DDL as an identifier
    with pytest.raises(RegistrationError):
        _safe_column(evil)


def test_sniff_schema_rejects_injected_column(monkeypatch):
    from queryskiff import engine as duck_engine
    monkeypatch.setattr(
        duck_engine, "schema_of",
        lambda ds: [{"column_name": 'x") WITH (y', "column_type": "BIGINT"}])
    with pytest.raises(RegistrationError):
        sniff_schema(_ds("id"))


def test_safe_location_rejects_quote_injection():
    with pytest.raises(RegistrationError):
        _safe_location("s3://b/k' ) WITH (format='CSV")
    assert _safe_location("s3://model-results/queryskiff-tables/t_x/") \
        == "s3://model-results/queryskiff-tables/t_x/"


def test_wrap_builds_cte_prelude_only_from_server_names():
    sql = _wrap("SELECT a.x FROM a JOIN b ON a.i = b.i LIMIT 5",
                [("a", "t_aaaa"), ("b", "t_bbbb")])
    assert sql.startswith('WITH "a" AS (SELECT * FROM ')
    assert '.t_aaaa)' in sql and '.t_bbbb)' in sql
    assert sql.endswith("SELECT a.x FROM a JOIN b ON a.i = b.i LIMIT 5")


def test_wrapped_sql_would_pass_trino_parse():
    import sqlglot
    sql = _wrap("SELECT count(*) c FROM rows JOIN prices ON rows.s = prices.s",
                [("rows", "t_1"), ("prices", "t_2")])
    parsed = sqlglot.parse(sql, read="trino")
    assert len(parsed) == 1
