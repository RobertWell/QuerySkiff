# HEL-113 — Trino as QuerySkiff's query layer: evaluation + decision record

PoC executed 2026-07-30 on the LAN box: single-node Trino 476 (docker, host
network, 3G heap) against the live MinIO, the live Postgres system of record,
and the legacy Oracle XE. Configs in `deploy/trino/` (secrets redacted),
benchmark harness + raw numbers in `deploy/trino/poc/`.

## 1. What was PROVEN (not assumed)

### Registration requirement (design Q1/Q2) — the decisive constraint
* **Arbitrary MinIO Parquet objects can NOT be queried directly.** A path-style
  query fails (`Schema 'datasets' does not exist`). Every dataset must be
  registered: `CREATE SCHEMA ... WITH (location=...)` +
  `CREATE TABLE ... WITH (external_location=..., format='PARQUET')`.
* Registration needs each table in its **own object prefix** (a shared prefix
  becomes one merged table) and a **manually declared column list** — the Hive
  connector does not infer Parquet schemas. QuerySkiff would need a
  registration service: copy/organize uploads into per-table prefixes, sniff
  schemas (ironically, with a Parquet reader like DuckDB), issue DDL, manage
  naming/evolution/collisions. That is the workflow cost DuckDB does not have
  (`read_parquet('s3://any/object')`, schema inferred).
* **No external metastore service is required** at this scale:
  `hive.metastore=file` (metadata as JSON in the bucket) worked throughout the
  PoC. Iceberg/HMS only become relevant with concurrent DDL or table evolution
  needs.

### Federation (design Q6/Q7)
* Postgres, Oracle XE and two MinIO tables all queryable from one engine
  (`pg.trdmgmr.*` 155,618-row signals table; `oracle.trdmgmr.stock_info`
  73,848 rows; `minio.ds.*` 329,798-row stock_hist).
* **Pushdown verified by EXPLAIN**: `SELECT count(*) ... WHERE horizon='LONG'`
  against Postgres is rewritten to a remote
  `SELECT count(*) ... WHERE "horizon" = ?` — filter AND aggregation execute in
  Postgres; Trino receives one row. Same connector family for Oracle.
* **Cross-source join (MinIO × Postgres)**: month of signals joined to MinIO
  price history: 0.375 s solo, 1.18 s median at 10 concurrent users. Data
  movement: the non-pushable side streams through the coordinator — bounded
  here, a real risk when the RDBMS side is large and unfiltered (document
  WHERE-clause discipline in any product surface).

### Concurrency, 10 users (acceptance: recorded vs DuckDB)
| shape | DuckDB solo | DuckDB c10 med | Trino solo | Trino c10 med |
|---|---|---|---|---|
| scan+group (330k rows) | 0.20 s | 0.75 s | 0.15 s | 0.53 s |
| filter+sort+limit | 0.11 s | 1.49 s | 0.21 s | 0.9–3.8 s |
| MinIO×MinIO join | 0.06 s | 0.45 s | 0.20 s | 0.96 s |
| MinIO×PG join | n/a (impossible) | n/a | 0.38 s | 1.18 s |

At current dataset sizes the engines are **equivalent in steady state**; DuckDB
is faster solo (no JVM/REST overhead). Two observed failure modes, one each:
* DuckDB: a 10-client burst starved MinIO HTTP and a follow-up GET **timed out**
  (mitigated with `http_retries`; the per-process model has no shared backoff).
* Trino: one 100× cold-start stall (filter c10 = 111 s shortly after restart;
  re-runs 1.0/3.8/1.0 s) — JIT/cache warmup tail-risk on a fresh coordinator.

### Governance (design Q4/Q5) — Trino's genuine advantage
* Per-session `query_max_run_time` enforced (killed at 2.2 s with
  `EXCEEDED_TIME_LIMIT`); resource groups/queueing available per user.
* Cancellation: instant (`cancel()` returned in 0.00 s; running query aborted
  `USER_CANCELED` — verify-by-doing).
* Everything observable in `system.runtime.queries` (user, state, timing) —
  multi-user attribution DuckDB cannot give without app-level bookkeeping.
* QuerySkiff's current bounds (semaphore=4, 60 s timeout, 1 GB memory, cancel
  Event) approximate this app-side — adequate, but per-process rather than
  shared.

### Cost (deliverable: operational estimate)
* Trino idle-to-light-load: **~1.6 GB RSS + ~1 CPU under query load**, JVM
  resident 24/7; plus the registration service, catalog secrets, and one more
  GitOps app to operate. DuckDB: zero idle cost, memory only during queries
  (bounded 1–4 GB per query), no standing service.

## 2. Decision record

| Option | Verdict | Why |
|---|---|---|
| Embedded DuckDB per query (current) | **KEEP for now** | Equal performance at current scale, zero idle cost, direct object inspection + schema inference = the product's core browse UX |
| Shared DuckDB service/worker pool | No | Adds a service without adding federation or governance; worst of both |
| Trino primary | Not yet | Registration workflow contradicts ad-hoc object inspection; standing cost unjustified at 2 datasets / ≤10 users |
| **Hybrid: DuckDB primary + Trino when triggers fire** | **RECOMMENDED** | Keeps today's UX; adoption path proven + configs versioned; clear triggers below |

**Adoption triggers** (any one): (a) cross-source joins (RDBMS × MinIO) become
a product requirement — impossible in the DuckDB path (well, `postgres_scanner`
exists but re-opens per query without governance); (b) working sets outgrow a
single query's memory budget (≈5–10 GB scans); (c) >~20 concurrent users
needing shared queueing/fairness; (d) curated, REGISTERED datasets become the
product model (registration cost then buys stable naming + governance instead
of fighting ad-hoc browsing).

**DuckDB's role if Trino is adopted** (acceptance item): stays as the
schema-sniffer for the registration service and the ad-hoc single-object
preview path; removed from the *workspace/join* execution path only.

## 3. Migration + rollback (design Q10), if/when triggered

1. Deploy Trino from `deploy/trino/` as a GitOps app (single
   coordinator, 3–4 G heap; workers only if scans demand).
2. Add a registration service endpoint (organize prefix → sniff schema via
   DuckDB → `CREATE TABLE`), keeping dataset ids opaque (HEL-90 contract).
3. Route **workspace queries** (HEL-112 multi-dataset) through Trino JDBC;
   keep single-dataset preview on DuckDB.
4. Rollback = route flag back to DuckDB; Trino is stateless above the bucket
   (file metastore lives beside the data; dropping it drops only registrations).

## 4. Interplay with open issues

* **HEL-95 (Kotlin/Quarkus migration)**: engine-neutral — `org.duckdb:duckdb_jdbc`
  (in-process) and `io.trino:trino-jdbc` are both plain JDBC from Kotlin. The
  migration should hide the engine behind the existing `engine` seam so the
  hybrid routing above is a config change. No reason to sequence HEL-95 behind
  Trino adoption.
* **HEL-112 (multi-dataset workspace + joins)**: implementable NOW on DuckDB —
  multiple `read_parquet` aliases in one connection, same bounds. The alias/
  authorization design should mirror the registration-service naming rules so
  a later Trino switch keeps the API.

## 5. PoC honesty notes

Ten-user tests used identical repeated queries (worst-case cache-friendly,
best-case contention); datasets are MB-scale — Trino's engine advantages are
unexercised at this size, which is precisely the point of the recommendation.
The 111 s Trino outlier and the DuckDB MinIO timeout are single observations,
reported as tail-risks, not rates.
