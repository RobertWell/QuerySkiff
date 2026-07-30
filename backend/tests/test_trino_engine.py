"""HEL-112/113: Trino engine + registrar units (no live Trino needed)."""
from __future__ import annotations

import pytest

from queryskiff.datasets import Dataset
from queryskiff.engine_trino import _wrap
from queryskiff.registrar import RegistrationError, _map_type, table_name


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
