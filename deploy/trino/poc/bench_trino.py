"""HEL-113: Trino-side measurements — same shapes as the DuckDB baseline,
plus MinIO×MinIO and MinIO×Postgres joins, pushdown verification, timeout and
cancellation behavior. Uses the REST client (what QuerySkiff's JDBC/HTTP
integration would see)."""
import concurrent.futures as cf
import json
import statistics
import sys
import time

import trino

HOST, PORT = "localhost", 8090


def conn():
    return trino.dbapi.connect(host=HOST, port=PORT, user="queryskiff", catalog="minio", schema="ds")


QUERIES = {
    "scan_agg": "SELECT stock_code, count(*) n, avg(close_price) c FROM minio.ds.stock_hist GROUP BY 1 ORDER BY n DESC LIMIT 10",
    "filter": "SELECT * FROM minio.ds.stock_hist WHERE stock_code='2330' ORDER BY trade_date DESC LIMIT 50",
    "join_mm": "SELECT h.stock_code, count(*) FROM minio.ds.stock_hist h JOIN minio.ds.realized_perf p ON CAST(h.trade_date AS date) = p.as_of_date GROUP BY 1 LIMIT 10",
    "join_xsrc": ("SELECT s.horizon, count(*) n, avg(h.close_price) FROM pg.trdmgmr.stock_trend_signals s "
                  "JOIN minio.ds.stock_hist h ON h.stock_code = s.stock_code AND CAST(h.trade_date AS date) = CAST(s.signal_date AS date) "
                  "WHERE s.signal_date >= DATE '2026-07-01' GROUP BY 1"),
}


def one(sql: str) -> float:
    t0 = time.perf_counter()
    c = conn()
    cur = c.cursor()
    cur.execute(sql)
    cur.fetchall()
    c.close()
    return time.perf_counter() - t0


out = {}
for name, sql in QUERIES.items():
    one(sql)  # warm
    solo = [one(sql) for _ in range(3)]
    t0 = time.perf_counter()
    with cf.ThreadPoolExecutor(max_workers=10) as ex:
        lat = list(ex.map(lambda _: one(sql), range(10)))
    out[name] = {
        "solo_median_s": round(statistics.median(solo), 3),
        "c10_median_s": round(statistics.median(lat), 3),
        "c10_p95_s": round(sorted(lat)[8], 3),
        "c10_wall_s": round(time.perf_counter() - t0, 3),
    }
    print(name, out[name], flush=True)

# pushdown: does the Postgres side receive the filter/aggregation?
c = conn()
cur = c.cursor()
cur.execute("EXPLAIN SELECT count(*) FROM pg.trdmgmr.stock_trend_signals WHERE horizon='LONG'")
plan = "\n".join(r[0] for r in cur.fetchall())
out["pg_pushdown"] = {
    "aggregation_pushed": "Aggregate" not in plan,   # no Trino-side Aggregate node => pushed to PG
    "table_scan_line": next((l.strip()[:160] for l in plan.splitlines() if "TableScan" in l or "trdmgmr" in l), "?"),
}
print("pg_pushdown:", out["pg_pushdown"], flush=True)

# timeout: session-level query_max_run_time
c = trino.dbapi.connect(host=HOST, port=PORT, user="qs-timeout",
                        session_properties={"query_max_run_time": "2s"})
cur = c.cursor()
t0 = time.perf_counter()
try:
    cur.execute("SELECT count(*) FROM minio.ds.stock_hist a CROSS JOIN minio.ds.stock_hist b")
    cur.fetchall()
    out["timeout_test"] = {"enforced": False}
except Exception as exc:
    out["timeout_test"] = {"enforced": True, "after_s": round(time.perf_counter() - t0, 1),
                           "error": str(exc)[:120]}
print("timeout:", out["timeout_test"], flush=True)

# cancellation: start a heavy query, cancel from another thread
c2 = trino.dbapi.connect(host=HOST, port=PORT, user="qs-cancel")
cur2 = c2.cursor()
t0 = time.perf_counter()
cur2.execute("SELECT count(*) FROM minio.ds.stock_hist a CROSS JOIN minio.ds.stock_hist b CROSS JOIN minio.ds.realized_perf")
cur2.cancel()
out["cancel_test"] = {"cancelled_after_s": round(time.perf_counter() - t0, 2)}
print("cancel:", out["cancel_test"], flush=True)

json.dump(out, open(sys.argv[1], "w"), indent=2)
