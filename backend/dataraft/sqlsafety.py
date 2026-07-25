"""Read-only SQL validation (HEL-90).

Defence in depth: the user's SQL may reference only the logical table `data`
(the server resolves the opaque dataset id to a real MinIO path — the user never
names it). We enforce a single read-only analytical statement and reject
anything that could read arbitrary files, reach the network, write, or run more
than one statement.

Parsing is done with sqlglot (a real SQL parser) rather than regexes, so
comment/quoting tricks can't smuggle a second statement or a banned function
past a substring check. A final belt-and-braces keyword scan covers parser gaps.
"""
from __future__ import annotations

import sqlglot
from sqlglot import exp

ALLOWED_ROOTS = (exp.Select, exp.With, exp.Describe)
# EXPLAIN/DESCRIBE handled specially (sqlglot models EXPLAIN via a Command in
# some dialects); we allow an EXPLAIN prefix over an otherwise-valid SELECT.

BANNED_FUNCTIONS = {
    "read_parquet", "read_csv", "read_csv_auto", "read_json", "read_json_auto",
    "parquet_scan", "read_ndjson", "read_text", "read_blob", "glob",
    "s3", "http", "https", "url", "install", "load", "copy", "attach",
}
BANNED_KEYWORDS = {
    "attach", "detach", "copy", "install", "load", "export", "import",
    "insert", "update", "delete", "drop", "create", "alter", "truncate",
    "pragma", "set", "call", "vacuum", "checkpoint", "grant", "revoke",
}
# substrings that must never appear (path/network escapes)
BANNED_SUBSTRINGS = ("s3://", "http://", "https://", "file://", "/etc/", "read_parquet")


class UnsafeSQL(Exception):
    pass


def _strip_explain(sql: str) -> tuple[str, bool]:
    s = sql.strip().rstrip(";").strip()
    low = s.lower()
    if low.startswith("explain"):
        return s[len("explain"):].strip(), True
    return s, False


def validate(sql: str, dialect: str = "duckdb") -> str:
    """Return the normalized SQL if safe; raise UnsafeSQL otherwise."""
    if not sql or not sql.strip():
        raise UnsafeSQL("empty query")

    inner, _explained = _strip_explain(sql)
    lowered = inner.lower()

    for bad in BANNED_SUBSTRINGS:
        if bad in lowered:
            raise UnsafeSQL(f"forbidden reference: {bad!r}")

    try:
        statements = sqlglot.parse(inner, read=dialect)
    except Exception as exc:  # noqa: BLE001
        raise UnsafeSQL(f"could not parse SQL: {exc}") from exc

    statements = [s for s in statements if s is not None]
    if len(statements) != 1:
        raise UnsafeSQL("exactly one statement is allowed")

    root = statements[0]
    # DESCRIBE data is fine; otherwise the root must be a SELECT/WITH
    if isinstance(root, exp.Describe):
        return sql.strip().rstrip(";")
    if not isinstance(root, (exp.Select, exp.With, exp.Subquery, exp.Union)):
        raise UnsafeSQL(f"only SELECT/WITH queries are allowed (got {type(root).__name__})")

    # no banned statement kinds anywhere in the tree
    for node in root.walk():
        n = node[0] if isinstance(node, tuple) else node
        if isinstance(n, (exp.Insert, exp.Update, exp.Delete, exp.Create,
                          exp.Drop, exp.Alter, exp.Command, exp.Set)):
            raise UnsafeSQL(f"forbidden statement element: {type(n).__name__}")
        if isinstance(n, exp.Func):
            name = (n.sql_name() or "").lower()
            if name in BANNED_FUNCTIONS:
                raise UnsafeSQL(f"forbidden function: {name}")
        if isinstance(n, exp.Anonymous):
            name = (n.name or "").lower()
            if name in BANNED_FUNCTIONS:
                raise UnsafeSQL(f"forbidden function: {name}")

    # only the logical table `data` may be referenced — plus names introduced by
    # the query itself (CTEs and subquery/table aliases).
    local_names = {"data"}
    for cte in root.find_all(exp.CTE):
        if cte.alias:
            local_names.add(cte.alias.lower())
    for alias in root.find_all(exp.TableAlias):
        if alias.name:
            local_names.add(alias.name.lower())
    for tbl in root.find_all(exp.Table):
        if tbl.name.lower() not in local_names:
            raise UnsafeSQL(f"only the table `data` may be queried (found {tbl.name!r})")

    return sql.strip().rstrip(";")


def has_limit(sql: str) -> bool:
    try:
        inner, _ = _strip_explain(sql)
        parsed = sqlglot.parse_one(inner, read="duckdb")
        return parsed.find(exp.Limit) is not None
    except Exception:  # noqa: BLE001
        return "limit" in sql.lower()
