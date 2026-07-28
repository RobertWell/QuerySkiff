"""Bounded server-side DuckDB query engine (HEL-90).

Every query runs in its own DuckDB connection configured to read the ONE
resolved dataset (as the logical table `data`) from MinIO. Bounds enforced:
global concurrency, per-query timeout, capped result rows, memory limit, temp
spill dir, and cancellation. The browser receives only rows/schema — never the
parquet file, credentials, or the real path.
"""
from __future__ import annotations

import threading
import uuid
from dataclasses import dataclass, field

import duckdb

from .config import config
from .datasets import Dataset, redact, s3_uri
from .sqlsafety import has_limit, validate

_semaphore = threading.BoundedSemaphore(config.max_running_queries)


@dataclass
class Query:
    id: str
    dataset_id: str
    sql: str
    status: str = "pending"          # pending|running|done|error|cancelled
    error: str | None = None
    columns: list[str] = field(default_factory=list)
    rows: list[list] = field(default_factory=list)
    row_count: int = 0
    truncated: bool = False
    _conn: duckdb.DuckDBPyConnection | None = None
    _cancel: threading.Event = field(default_factory=threading.Event)


_queries: dict[str, Query] = {}
_lock = threading.Lock()


def _new_connection(ds: Dataset) -> duckdb.DuckDBPyConnection:
    conn = duckdb.connect(database=":memory:")
    conn.execute(f"SET memory_limit='{config.memory_limit}'")
    conn.execute(f"SET temp_directory='{config.temp_dir}'")
    # enable_external_access defaults to true and CANNOT be SET after startup
    conn.execute("INSTALL httpfs")
    conn.execute("LOAD httpfs")
    conn.execute(f"SET s3_endpoint='{config.minio_endpoint}'")
    conn.execute(f"SET s3_access_key_id='{config.minio_access_key}'")
    conn.execute(f"SET s3_secret_access_key='{config.minio_secret_key}'")
    conn.execute(f"SET s3_use_ssl={'true' if config.minio_secure else 'false'}")
    conn.execute("SET s3_url_style='path'")
    # Expose the resolved dataset ONLY as `data`. The path is passed through the
    # native read_parquet relation API — as a bound Python argument, NOT
    # interpolated into a SQL string — so a crafted key (the dataset id is
    # client-supplied) cannot break out of a string literal and inject SQL.
    conn.read_parquet(s3_uri(ds)).create_view("data", replace=True)
    return conn


def create_query(ds: Dataset, sql: str) -> Query:
    safe = validate(sql)                       # raises UnsafeSQL
    if not has_limit(safe):
        safe = f"SELECT * FROM ({safe}) AS _sub LIMIT {config.default_limit}"
    q = Query(id=uuid.uuid4().hex, dataset_id=ds.dataset_id, sql=safe)
    with _lock:
        _queries[q.id] = q
    threading.Thread(target=_run, args=(q, ds), daemon=True).start()
    return q


def _run(q: Query, ds: Dataset) -> None:
    acquired = _semaphore.acquire(timeout=config.timeout_seconds)
    if not acquired:
        q.status, q.error = "error", "server busy (max concurrent queries)"
        return
    try:
        if q._cancel.is_set():
            q.status = "cancelled"
            return
        q.status = "running"
        conn = _new_connection(ds)
        q._conn = conn
        timer = threading.Timer(config.timeout_seconds, conn.interrupt)
        timer.start()
        try:
            rel = conn.execute(q.sql)
            q.columns = [d[0] for d in rel.description] if rel.description else []
            fetched = rel.fetchmany(config.max_result_rows + 1)
            if len(fetched) > config.max_result_rows:
                q.truncated = True
                fetched = fetched[:config.max_result_rows]
            q.rows = [list(r) for r in fetched]
            q.row_count = len(q.rows)
            q.status = "cancelled" if q._cancel.is_set() else "done"
        finally:
            timer.cancel()
            conn.close()
            q._conn = None
    except Exception as exc:  # noqa: BLE001
        if q._cancel.is_set():
            q.status = "cancelled"
        else:
            # redact any s3://bucket/key the DuckDB/MinIO error may echo (HEL-90)
            q.status, q.error = "error", redact(str(exc).splitlines()[0])[:500]
    finally:
        _semaphore.release()


def get_query(qid: str) -> Query | None:
    with _lock:
        return _queries.get(qid)


def cancel_query(qid: str) -> bool:
    q = get_query(qid)
    if not q or q.status in ("done", "error", "cancelled"):
        return False
    q._cancel.set()
    if q._conn is not None:
        try:
            q._conn.interrupt()
        except Exception:  # noqa: BLE001
            pass
    return True


def schema_of(ds: Dataset) -> list[dict]:
    conn = _new_connection(ds)
    try:
        rel = conn.execute("DESCRIBE SELECT * FROM data")
        cols = [d[0] for d in rel.description]
        return [dict(zip(cols, r)) for r in rel.fetchall()]
    finally:
        conn.close()
