"""Bounded Trino query engine (HEL-112/113) — same public surface as the
DuckDB engine, selected by QUERYSKIFF_ENGINE=trino.

Workspace aliases are mapped onto auto-registered tables with a CTE prelude:

    WITH "alias1" AS (SELECT * FROM cat.sch.t_x), ... <user sql>

CTEs shadow table resolution, are inlined by the optimizer (pushdown intact),
require no per-query DDL, and keep the user's SQL referencing ONLY validated
alias names — catalog/schema/table names never appear in user-visible text.

Bounds: shared semaphore sized like the DuckDB path (Trino's own resource
governance is the real limiter — the semaphore just caps this pod's in-flight
requests), per-session query_max_run_time, capped result rows, instant
cursor.cancel(). Errors are redacted before reaching the browser.
"""
from __future__ import annotations

import threading
import uuid

from .config import config
from .datasets import Dataset, redact
from .engine import Query                      # shared dataclass + status shape
from .registrar import ensure_registered
from .sqlsafety import has_limit, validate

_semaphore = threading.BoundedSemaphore(config.max_running_queries)
_queries: dict[str, Query] = {}
_lock = threading.Lock()


def _connect(user: str = "queryskiff"):
    import trino
    return trino.dbapi.connect(
        host=config.trino_host, port=config.trino_port, user=user,
        catalog=config.trino_catalog, schema=config.trino_schema,
        session_properties={"query_max_run_time": f"{config.timeout_seconds}s"},
    )


def _wrap(sql: str, tables: list[tuple[str, str]]) -> str:
    """Prepend the alias->table CTE prelude. Alias names are pre-validated
    identifiers; table names are server-generated (t_<hash>)."""
    ctes = ", ".join(
        f'"{alias}" AS (SELECT * FROM {config.trino_catalog}.{config.trino_schema}.{t})'
        for alias, t in tables)
    return f"WITH {ctes} {sql}"


def create_query(entries: list[tuple[Dataset, str]], sql: str) -> Query:
    aliases = frozenset(a for _, a in entries)
    safe = validate(sql, dialect="trino", allowed_tables=aliases)
    if not has_limit(safe):
        safe = f"SELECT * FROM ({safe}) AS _sub LIMIT {config.default_limit}"
    q = Query(id=uuid.uuid4().hex, dataset_id=entries[0][0].dataset_id, sql=safe,
              dataset_ids=[d.dataset_id for d, _ in entries],
              aliases=[a for _, a in entries])
    with _lock:
        _queries[q.id] = q
    threading.Thread(target=_run, args=(q, entries), daemon=True).start()
    return q


def _run(q: Query, entries: list[tuple[Dataset, str]]) -> None:
    acquired = _semaphore.acquire(timeout=config.timeout_seconds)
    if not acquired:
        q.status, q.error = "error", "server busy (max concurrent queries)"
        return
    cur = None
    try:
        if q._cancel.is_set():
            q.status = "cancelled"
            return
        q.status = "running"
        tables = [(alias, ensure_registered(_connect, ds)) for ds, alias in entries]
        conn = _connect()
        cur = conn.cursor()
        q._conn = cur                     # duck-typed: cancel_query calls .interrupt
        cur.interrupt = cur.cancel        # noqa: SLF001 — align with the DuckDB surface
        cur.execute(_wrap(q.sql, tables))
        fetched = cur.fetchmany(config.max_result_rows + 1)
        q.columns = [d[0] for d in cur.description] if cur.description else []
        if len(fetched) > config.max_result_rows:
            q.truncated = True
            fetched = fetched[:config.max_result_rows]
        q.rows = [list(r) for r in fetched]
        q.row_count = len(q.rows)
        q.status = "cancelled" if q._cancel.is_set() else "done"
        conn.close()
        q._conn = None
    except Exception as exc:  # noqa: BLE001
        if q._cancel.is_set():
            q.status = "cancelled"
        else:
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
    """Schema via the registered table (registers on first touch). Shape
    mirrors the DuckDB DESCRIBE dicts the API already exposes."""
    tname = ensure_registered(_connect, ds)
    conn = _connect()
    try:
        cur = conn.cursor()
        cur.execute(f"DESCRIBE {config.trino_catalog}.{config.trino_schema}.{tname}")
        return [{"column_name": r[0], "column_type": r[1], "null": "YES",
                 "key": None, "default": None, "extra": None}
                for r in cur.fetchall()]
    finally:
        conn.close()
