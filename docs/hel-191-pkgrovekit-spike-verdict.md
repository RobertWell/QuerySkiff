# HEL-191 — PkgroveKit-backed virtual-dataset promotion: GO/NO-GO verdict

**Spike branch:** `spike/hel-191-pkgrovekit-promotion` (branch-only; not merged).
**Spike code:** `spike/hel-191/` (isolated Maven module, throwaway — deletes with the branch).
**Date:** 2026-08-09 · **Executed on:** LAN box, Temurin JDK 21, offline.

## Verdict: **NO-GO**

Do **not** adopt PkgroveKit `Transfer` for QuerySkiff virtual-dataset promotion, and do
**not** create a `pkgrovekit-trino` adapter. Promotion should stay **engine-side SQL**
(`CREATE TABLE AS SELECT` / `INSERT … SELECT`). PkgroveKit `Transfer` moves rows through
the JVM, which is only worth doing when the source and target are *different* engines that
cannot see each other's data. In QuerySkiff, the promotion target reads the **same MinIO
Parquet** the source reads — DuckDB via `read_parquet('s3://…')`, Trino via the registered
Hive external table (`Registrar.kt`) — so the engine can materialize the dataset itself in
one statement. Measured, engine-side CTAS is **543× faster** than the default `Transfer`
path and still **7× faster** than PkgroveKit's fastest path (its DuckDB appender bulk
loader), while being one line of SQL with no adapter, no row marshaling, and no new
dependency. Type fidelity and failure reporting of `Transfer` are both good — this is
**not** a NO-GO on quality, it is a NO-GO on architecture: row transfer solves a problem
QuerySkiff does not have.

## Precondition (HEL-131)

Gated on **HEL-131 (QuerySkiff production soak sign-off)**, which reached **Done on
2026-08-08** (state history Todo→In Review→Done; DoD includes an executed rollback
rehearsal and full-window soak evidence). The precondition is **met**. This spike carried
no production risk regardless: it is branch-only, touches no prod MinIO/Trino, and uses
local Parquet fixtures.

## What was measured

300,000 rows across 3 local Parquet files; columns exercising the fidelity-sensitive
types: `BIGINT`, `TIMESTAMP` (microsecond), `DECIMAL(18,4)`, `VARCHAR` (accents + CJK +
emoji), `BOOLEAN`. Source = in-memory DuckDB reading the 3 files via `read_parquet([...])`
(the same reader path `DuckDbEngine.kt` uses in prod, minus the S3 endpoint settings).
Target = file-backed DuckDB managed table. Warm-up transfer excluded from timing.

### Throughput

| Path | Mechanism | Wall | Throughput |
|---|---|---|---|
| PkgroveKit `Transfer` (default) | JDBC prepared-statement batches (1000/batch), row-by-row through the JVM | ~103 s | **~2,920 rows/s** |
| PkgroveKit `Transfer` (`useBulkLoad=true`) | PkgroveKit's DuckDB appender bulk loader | ~1.36 s | **~221,000 rows/s** |
| **Engine-side CTAS** | `CREATE TABLE … AS SELECT * FROM read_parquet([...])` | **~0.19 s** | **~1,586,000 rows/s** |

**CTAS is ~543× faster than default `Transfer` and ~7× faster than bulk `Transfer`.**
(Single-run wall times on a warm JVM; the ratios, not the absolute ms, are the finding.)

### Type-mapping fidelity (id=0 probe row)

Both the `Transfer` table and the CTAS table reproduced the source **exactly**, and their
column types were **identical** — no gaps:

- **Timestamp:** `2026-08-09 13:45:06.123456` — microsecond precision preserved on both.
- **Decimal:** `0.6789` with `DECIMAL(18,4)` scale preserved (scale=4) on both.
- **Unicode:** `row 0 café 中文 🚀 ⓠ` (accents, CJK, astral-plane emoji, enclosed
  alphanumeric) preserved on both.
- Column types Transfer vs CTAS: `{id=BIGINT, event_ts=TIMESTAMP, amount=DECIMAL(18,4),
  label=VARCHAR, active=BOOLEAN}` — **identical, zero type-mapping gaps** for the
  DuckDB→DuckDB path.

Type-mapping gaps that *would* appear on a real Trino/Iceberg target (from the prototype
dialect + `Registrar.kt`'s existing DuckDB→Trino map, not executed here): Iceberg has no
`tinyint`/`smallint` (must widen to `integer`) and no unsigned integers; DuckDB `HUGEINT`
maps to `decimal(38,0)`; `time`/`timestamp` must carry microsecond precision (`time(6)`,
`timestamp(6)`). These are handled the same way whether promotion is CTAS or row-transfer,
so they do not favor `Transfer`.

### Injected-failure reporting

Forced a primary-key collision mid-load (`APPEND` into a table with `id BIGINT PRIMARY
KEY`, source deliberately containing duplicate ids). PkgroveKit surfaced it cleanly:

```
threw: BatchWriteException: batch 4 failed (rows 4000..4999); 0 rows previously committed
rows actually committed to managed_fail after failure: 0
```

Precise failing batch index **and** row range, and it was transactional — **0 rows
committed**, no partial write. `OperationReport` also carries `failedBatchIndex` /
`failedRowRange` for the non-throwing paths. This is genuinely good and is a point in
PkgroveKit's favor — but engine-side CTAS is likewise atomic (the statement either creates
the table or does not), so it is not a differentiator for this use case.

### Connection / view cleanup

- After a successful `Transfer` the target DB contained **only** `managed_pk` — no staging
  or temp tables leaked.
- Connections are **caller-owned**: `Transfer` did not close the source or target
  connection we passed (verified `!isClosed` afterward), matching the ownership contract;
  the caller closes them.
- No stray temp files beyond the intended target `.db` files.

## Trino/Iceberg dialect prototype

`spike/hel-191/src/main/kotlin/com/queryskiff/spike/TrinoIcebergDialect.kt` — a minimal
`SqlDialect` implemented by Kotlin interface delegation to the shipped `DuckDbDialect`,
overriding only the Trino/Iceberg surface (type mapping, CREATE/APPEND DDL, parameterised
INSERT). It generates plausible promotion SQL (asserted as strings; **not** executed —
no Trino server was reachable locally):

```
CREATE : CREATE TABLE iceberg.queryskiff."t_promoted" ("id" bigint, "event_ts" timestamp(6),
         "amount" decimal(18,4), "label" varchar, "active" boolean) WITH (format = 'PARQUET')
APPEND : CREATE TABLE IF NOT EXISTS iceberg.queryskiff."t_promoted" (...) WITH (format = 'PARQUET')
INSERT : INSERT INTO iceberg.queryskiff."t_promoted" ("id","event_ts","amount","label","active")
         VALUES (?, ?, ?, ?, ?)
```

The prototype deliberately **declines** to offer a JVM bulk loader (`bulkLoader()` throws),
because the correct Trino/Iceberg promotion is `INSERT INTO iceberg… SELECT * FROM hive…`
executed **inside Trino** over the already-registered external table — no rows crossing the
JVM. Building the loader would be building the slower path.

## Preferred architecture

Keep the current design and make promotion an engine-side operation behind the existing
engine seam:

- **DuckDB engine (default):** `markManaged()` in `VirtualDatasets.kt` should trigger a
  `CREATE TABLE <managed> AS SELECT * FROM read_parquet([<members>])` against a durable
  DuckDB/MinIO target (respecting `schemaPolicy`: `union_by_name=true` when set). One
  statement, native reader, ~1.6M rows/s here.
- **Trino engine (when HEL-113 triggers fire):** promotion is
  `CREATE TABLE iceberg.<sch>.<t> AS SELECT * FROM <registered hive external table>` — the
  Registrar already turns the dataset into a Trino table with no data scan, so Trino reads
  the source Parquet directly.
- **PkgroveKit's real niche** (cross-engine row transfer with governance/failure reporting)
  is a genuine strength, just not this problem. It stays the right tool where AuditPatchX
  uses it (Oracle→X). QuerySkiff's promotion never crosses engines.

`VirtualDatasets.kt` today only mutates registry state (`markManaged` sets the
catalog/schema/table pointer); the actual materialization is still unbuilt. This verdict
says: build it as engine-side CTAS, not as PkgroveKit row transfer.

## `pkgrovekit-trino` adapter — not justified

Per the owner's approval note ("do not create a `pkgrovekit-trino` module unless the spike
proves it is preferable to CTAS and has a credible second consumer"):

1. **Not preferable to CTAS** — measured 7×+ slower even at its best, and it would move
   rows the engine can already materialize in place.
2. **No credible second consumer** — the audit question asks for ≥1 real consumer beyond
   QuerySkiff; none exists. The only current PkgroveKit consumer (AuditPatchX) does
   cross-engine RDBMS transfer where a Trino/Iceberg *append* adapter is irrelevant.

**Do not file a `pkgrovekit-trino` enhancement issue.** The spike branch should be deleted
or archived after this verdict is recorded (its only artifacts are this doc and the
throwaway `spike/` module).

## What could NOT be run locally (honest limitations)

- **No live Trino/Iceberg execution.** No Trino server was reachable from the spike box, so
  the `TrinoIcebergDialect` output was validated as SQL strings only, not executed. The
  Trino throughput claim rests on the HEL-113 PoC (engine-side, pushdown-verified) plus the
  architectural argument, not a fresh append benchmark.
- **No prod MinIO / S3.** Per the task rules, fixtures were local Parquet files read through
  DuckDB's Parquet reader; the production S3/httpfs session settings were not exercised
  (they do not affect the Transfer-vs-CTAS comparison, which is about where the rows flow).
- **Single-run timings** on one warm JVM; reported as ratios, not SLAs. DuckDB→DuckDB only
  for the executed benchmark; a DuckDB→Trino `Transfer` (the cross-engine case) was not
  benchmarked because the verdict is precisely that QuerySkiff should never do it.
- **Toolchain note:** the box's default JDK is 17 but all PkgroveKit 0.5.0 artifacts are
  Java-21 bytecode; the spike was built/run with Temurin 21 (`~/.jdks/temurin-21`).
