"""SQL-safety tests (HEL-90) — the security boundary of QuerySkiff."""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from queryskiff.sqlsafety import UnsafeSQL, has_limit, validate


@pytest.mark.parametrize("sql", [
    "SELECT * FROM data LIMIT 500",
    "SELECT symbol, score FROM data WHERE score > 0.6 ORDER BY score DESC LIMIT 10",
    "WITH t AS (SELECT * FROM data) SELECT count(*) FROM t",
    "SELECT symbol, avg(score) FROM data GROUP BY symbol HAVING avg(score) > 0.5",
    "SELECT *, row_number() OVER (ORDER BY score) FROM data",
    "EXPLAIN SELECT * FROM data",
    "DESCRIBE data",
])
def test_allows_read_only(sql):
    assert validate(sql)


@pytest.mark.parametrize("sql", [
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
    "SELECT * FROM data; DROP TABLE data",          # multi-statement
    "SELECT * FROM data; SELECT * FROM data",        # multi-statement
    "SELECT * FROM other_table",                      # wrong table
    "SELECT * FROM read_csv('/etc/passwd')",
    "SELECT * FROM data WHERE x IN (SELECT * FROM read_parquet('s3://a/b'))",
])
def test_rejects_unsafe(sql):
    with pytest.raises(UnsafeSQL):
        validate(sql)


def test_rejects_empty():
    with pytest.raises(UnsafeSQL):
        validate("   ")


def test_has_limit():
    assert has_limit("SELECT * FROM data LIMIT 10")
    assert not has_limit("SELECT * FROM data")
    assert not has_limit("SELECT count(*) FROM data")


def test_comment_smuggle_second_statement_blocked():
    with pytest.raises(UnsafeSQL):
        validate("SELECT * FROM data -- \n; DROP TABLE data")


def test_case_and_whitespace_insensitive():
    with pytest.raises(UnsafeSQL):
        validate("  sElEcT * FROM data; InSeRt INTO data VALUES (1) ")
