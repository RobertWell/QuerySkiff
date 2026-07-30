"""HEL-113 baseline: the CURRENT QuerySkiff execution model (per-query
in-memory DuckDB over MinIO parquet) under 1 and 10 concurrent users.
Mirrors engine.py's connection setup (httpfs + s3 config + memory limit)."""
import concurrent.futures as cf
import json
import os
import statistics
import sys
import time

import duckdb

ENDPOINT = "10.152.183.50:9000"
AK = os.environ["MINIO_ACCESS_KEY"]
SK = os.environ["MINIO_SECRET_KEY"]
HIST = "s3://model-results/datasets/stock_hist_90d.parquet"
PERF = "s3://model-results/datasets/realized_perf.parquet"

QUERIES = {
    "scan_agg": f"SELECT stock_code, count(*) n, avg(close_price) c FROM read_parquet('{HIST}') GROUP BY 1 ORDER BY n DESC LIMIT 10",
    "filter": f"SELECT * FROM read_parquet('{HIST}') WHERE stock_code='2330' ORDER BY trade_date DESC LIMIT 50",
    "join_mm": f"SELECT h.stock_code, count(*) FROM read_parquet('{HIST}') h JOIN read_parquet('{PERF}') p ON h.trade_date = p.as_of_date GROUP BY 1 LIMIT 10",
}


def one(sql: str) -> float:
    t0 = time.perf_counter()
    conn = duckdb.connect(":memory:")
    conn.execute("SET memory_limit='1GB'")
    conn.execute("INSTALL httpfs; LOAD httpfs")
    conn.execute(f"SET s3_endpoint='{ENDPOINT}'")
    conn.execute("SET s3_use_ssl=false")
    conn.execute("SET http_timeout=60000")
    conn.execute("SET http_retries=3")
    conn.execute("SET s3_url_style='path'")
    conn.execute(f"SET s3_access_key_id='{AK}'")
    conn.execute(f"SET s3_secret_access_key='{SK}'")
    conn.execute(sql).fetchall()
    conn.close()
    return time.perf_counter() - t0


out = {}
for name, sql in QUERIES.items():
    one(sql)  # warm (OS page cache on minio side)
    solo = [one(sql) for _ in range(3)]
    t0 = time.perf_counter()
    with cf.ThreadPoolExecutor(max_workers=10) as ex:
        lat = list(ex.map(lambda _: one(sql), range(10)))
    wall10 = time.perf_counter() - t0
    out[name] = {
        "solo_median_s": round(statistics.median(solo), 3),
        "c10_median_s": round(statistics.median(lat), 3),
        "c10_p95_s": round(sorted(lat)[8], 3),
        "c10_wall_s": round(wall10, 3),
    }
    print(name, out[name], flush=True)
json.dump(out, open(sys.argv[1], "w"), indent=2)
