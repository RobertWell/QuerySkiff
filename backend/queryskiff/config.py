"""QuerySkiff configuration (HEL-90).

All settings come from environment variables (secrets injected by the platform,
never committed). The browser never sees any of these — especially the MinIO
credentials, which stay server-side by construction.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field


def _env(name: str, default: str | None = None) -> str | None:
    """HEL-98 transition: QUERYSKIFF_* is canonical; DATARAFT_* is honored as a
    fallback so an environment set before the rename keeps working. Remove the
    fallback after the transition window — one namespace, not two."""
    v = os.getenv(name)
    if v is None:
        v = os.getenv(name.replace("QUERYSKIFF_", "DATARAFT_", 1))
    return default if v is None else v


def _int(name: str, default: int) -> int:
    try:
        return int(_env(name, str(default)))
    except ValueError:
        return default


def _csv(name: str, default: str) -> list[str]:
    return [x.strip() for x in _env(name, default).split(",") if x.strip()]


@dataclass(frozen=True)
class Config:
    base_path: str = _env("QUERYSKIFF_BASE_PATH", "/queryskiff")

    minio_endpoint: str = os.getenv("MINIO_ENDPOINT", "minio.minio.svc.cluster.local:9000")
    minio_access_key: str = os.getenv("MINIO_ACCESS_KEY", "")
    minio_secret_key: str = os.getenv("MINIO_SECRET_KEY", "")
    minio_secure: bool = os.getenv("MINIO_SECURE", "false").lower() == "true"

    # only these buckets/prefixes are browsable; nothing else is reachable
    allowed_buckets: list[str] = field(
        default_factory=lambda: _csv("QUERYSKIFF_ALLOWED_BUCKETS", "stable-stock,model-results"))

    # HEL-112/113: query engine. "duckdb" (default, per-query embedded) or
    # "trino" (shared engine + auto-registration). Rollback = flip the flag.
    engine: str = _env("QUERYSKIFF_ENGINE", "duckdb")
    trino_host: str = _env("QUERYSKIFF_TRINO_HOST", "trino.trino.svc.cluster.local")
    trino_port: int = _int("QUERYSKIFF_TRINO_PORT", 8080)
    trino_catalog: str = _env("QUERYSKIFF_TRINO_CATALOG", "minio")
    trino_schema: str = _env("QUERYSKIFF_TRINO_SCHEMA", "ds")
    # managed per-table prefixes for auto-registration (server-side copies of
    # loose parquet objects live here; bucket must be in allowed_buckets' MinIO)
    trino_managed_bucket: str = _env("QUERYSKIFF_TRINO_MANAGED_BUCKET", "model-results")
    trino_managed_prefix: str = _env("QUERYSKIFF_TRINO_MANAGED_PREFIX", "queryskiff-tables/")

    default_limit: int = _int("QUERYSKIFF_DEFAULT_LIMIT", 500)
    max_result_rows: int = _int("QUERYSKIFF_MAX_RESULT_ROWS", 10_000)
    # HEL-112 multi-user: DuckDB's low cap protects per-process memory; under
    # Trino the engine governs memory/queueing globally, so this pod can admit
    # far more in-flight requests (override explicitly to tune either way).
    max_running_queries: int = _int(
        "QUERYSKIFF_MAX_RUNNING_QUERIES",
        16 if _env("QUERYSKIFF_ENGINE", "duckdb") == "trino" else 4)
    timeout_seconds: int = _int("QUERYSKIFF_TIMEOUT_SECONDS", 60)
    memory_limit: str = _env("QUERYSKIFF_MEMORY_LIMIT", "4GB")
    temp_dir: str = _env("QUERYSKIFF_TEMP_DIR", "/tmp/queryskiff-duckdb")


config = Config()
