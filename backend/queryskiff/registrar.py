"""Invisible auto-registration of datasets as Trino tables (HEL-113 design).

Parquet is self-describing (the footer carries the full schema), so a dataset
becomes a Trino table with NO data scan:

  1. layout   — a loose object is server-side-copied into its own managed
                prefix (Hive external tables bind to a directory); a folder
                dataset already IS a directory and is registered in place.
  2. sniff    — schema read from the parquet footer (DuckDB DESCRIBE over the
                object: reads footer + metadata only).
  3. register — idempotent CREATE TABLE IF NOT EXISTS named by a hash of the
                opaque dataset id, in the shared catalog schema.

Registrations are GLOBAL and shared by all users (one table per dataset
version). A source object change (etag) re-materializes and re-registers.
The browser never sees table names, prefixes, or DDL — the query layer maps
workspace aliases onto registered tables server-side.
"""
from __future__ import annotations

import hashlib
import threading

from .config import config
from .datasets import Dataset, s3_uri

_lock = threading.Lock()
# dataset_id -> (source_etag, table_name); in-memory: re-sniffing after a pod
# restart costs one footer read + idempotent DDL, so no persistence needed.
_registered: dict[str, tuple[str, str]] = {}

# DuckDB DESCRIBE type -> Trino DDL type. Unknown types fail registration
# loudly rather than guessing (fail-closed; the DuckDB engine path remains
# available for such files).
_TYPE_MAP = {
    "BOOLEAN": "boolean", "TINYINT": "tinyint", "SMALLINT": "smallint",
    "INTEGER": "integer", "BIGINT": "bigint", "HUGEINT": "decimal(38,0)",
    "UTINYINT": "smallint", "USMALLINT": "integer", "UINTEGER": "bigint",
    "UBIGINT": "decimal(20,0)",
    "FLOAT": "real", "DOUBLE": "double",
    "VARCHAR": "varchar", "BLOB": "varbinary",
    "DATE": "date", "TIME": "time",
    "TIMESTAMP": "timestamp", "TIMESTAMP WITH TIME ZONE": "timestamp with time zone",
}


class RegistrationError(RuntimeError):
    pass


def table_name(ds: Dataset) -> str:
    return "t_" + hashlib.sha256(ds.dataset_id.encode()).hexdigest()[:16]


def _map_type(duck_type: str) -> str:
    t = duck_type.strip().upper()
    if t.startswith("DECIMAL"):
        return t.lower()
    mapped = _TYPE_MAP.get(t)
    if not mapped:
        raise RegistrationError(f"unsupported column type for registration: {duck_type}")
    return mapped


def sniff_schema(ds: Dataset) -> list[tuple[str, str]]:
    """[(column, trino_type)] from the parquet footer via DuckDB DESCRIBE."""
    from . import engine as duck_engine
    rows = duck_engine.schema_of(ds)          # column_name / column_type dicts
    out = []
    for r in rows:
        out.append((str(r["column_name"]), _map_type(str(r["column_type"]))))
    if not out:
        raise RegistrationError("dataset has no columns")
    return out


def _source_etag(ds: Dataset) -> str:
    from minio import Minio
    client = Minio(config.minio_endpoint, access_key=config.minio_access_key,
                   secret_key=config.minio_secret_key, secure=config.minio_secure)
    if ds.is_folder:
        # folder datasets register in place; version by the part listing
        parts = sorted((o.object_name, o.etag) for o in
                       client.list_objects(ds.bucket, prefix=ds.key, recursive=True)
                       if o.object_name.lower().endswith(".parquet"))
        return hashlib.sha256(repr(parts).encode()).hexdigest()[:16]
    return client.stat_object(ds.bucket, ds.key).etag or "unknown"


def _materialize(ds: Dataset, tname: str) -> str:
    """Ensure the dataset lives in a directory Trino can bind to; return the
    external_location. Loose file -> server-side copy into the managed prefix
    (no data download); folder dataset -> its own prefix, in place."""
    if ds.is_folder:
        return f"s3://{ds.bucket}/{ds.key}"
    from minio import Minio
    from minio.commonconfig import CopySource
    client = Minio(config.minio_endpoint, access_key=config.minio_access_key,
                   secret_key=config.minio_secret_key, secure=config.minio_secure)
    dst_key = f"{config.trino_managed_prefix}{tname}/part-0.parquet"
    client.copy_object(config.trino_managed_bucket, dst_key,
                       CopySource(ds.bucket, ds.key))
    return f"s3://{config.trino_managed_bucket}/{config.trino_managed_prefix}{tname}/"


def ensure_registered(trino_conn_factory, ds: Dataset) -> str:
    """Idempotent: returns the Trino table name for the dataset, registering
    (and re-registering on source change) as needed. Thread-safe."""
    etag = _source_etag(ds)
    with _lock:
        cached = _registered.get(ds.dataset_id)
        if cached and cached[0] == etag:
            return cached[1]
    tname = table_name(ds)
    cols = sniff_schema(ds)
    location = _materialize(ds, tname)
    ddl_cols = ", ".join(f'"{c}" {t}' for c, t in cols)
    conn = trino_conn_factory()
    try:
        cur = conn.cursor()
        cur.execute(f"CREATE SCHEMA IF NOT EXISTS "
                    f"{config.trino_catalog}.{config.trino_schema} "
                    f"WITH (location = 's3://{config.trino_managed_bucket}/"
                    f"{config.trino_managed_prefix}')")
        cur.fetchall()
        full = f"{config.trino_catalog}.{config.trino_schema}.{tname}"
        if cached:  # source changed: drop the stale registration first
            cur.execute(f"DROP TABLE IF EXISTS {full}")
            cur.fetchall()
        cur.execute(f"CREATE TABLE IF NOT EXISTS {full} ({ddl_cols}) "
                    f"WITH (external_location = '{location}', format = 'PARQUET')")
        cur.fetchall()
    finally:
        conn.close()
    with _lock:
        _registered[ds.dataset_id] = (etag, tname)
    return tname
