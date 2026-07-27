# HEL-95 — JVM SQL-parser decision

**Decision: JSQLParser 4.9**, used with the SAME layered defence-in-depth as the
Python `sqlsafety.validate` (not a single-mechanism rewrite). Verified: the full
contract corpus (7 allow + 20 reject) passes identically — `backend-jvm`
`SqlPolicyParityTest`, 28/28.

## Why a spike before scaffolding

The spec calls SQL safety AST/parser-based and forbids downgrading to
regex/keyword matching. The real migration risk was never the *reject* side —
it was whether a JVM parser could **accept** the DuckDB-flavoured allowed corpus
(`EXPLAIN SELECT …`, `DESCRIBE data`, window functions, CTEs) without a DuckDB
grammar. If it couldn't, the whole port stalls. So the parser was proven against
the corpus in isolation first.

## What the spike found (the load-bearing detail)

sqlglot's policy is **layered**, and the layer that matters most is a
banned-**substring** pre-scan (`read_parquet`, `read_csv`, `s3://`, `/etc/`, …)
run *before* AST analysis. The first Kotlin attempt used only a FROM-tree table
allow-list — and 3 corpus entries slipped through:

- `SELECT * FROM read_parquet('s3://secret/x.parquet')`
- `SELECT * FROM read_csv('/etc/passwd')`
- `SELECT * FROM data WHERE x IN (SELECT * FROM read_parquet('s3://a/b'))`

JSQLParser's `TablesNamesFinder` does **not** surface table functions as tables,
and the third hides the function inside a WHERE subquery a FROM-walk never
reaches. The substring pre-scan (which the Python also relies on) catches all
three regardless of position. Lesson: the port must reproduce the *layering*,
not just the AST allow-list.

## Layers (matching Python exactly)

1. strip a leading `EXPLAIN`; banned-substring scan on the lowered text.
2. real parse (`CCJSqlParserUtil.parseStatements`); exactly one statement or
   fail-closed on parse error.
3. statement class ∈ { SELECT (incl. WITH/set-ops), EXPLAIN SELECT, DESCRIBE }.
4. table allow-list: every referenced table is `data` or a query-local CTE.

## Divergences recorded

- Python's `BANNED_KEYWORDS` set is dead code (`validate()` never uses it) — not
  ported; statement-class + single-statement enforcement covers those.
- Accepted tradeoff (identical to Python): a query whose *string literal*
  contains `s3://`/`read_parquet` is rejected. A legitimate analytical query
  over the fixed `data` view does not; matching Python's behaviour is the
  contract.

## Next

Grow `backend-jvm` into the full Quarkus backend (api/service/model/sql/duckdb/
minio/config per AuditPatchX), DuckDB-JDBC engine, MinIO Java SDK dataset layer,
then run the dual-target contract suite (`QUERYSKIFF_CONTRACT_URL`) against the
Quarkus canary as the parity gate.
