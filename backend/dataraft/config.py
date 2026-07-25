"""Dataraft configuration (HEL-90).

All settings come from environment variables (secrets injected by the platform,
never committed). The browser never sees any of these — especially the MinIO
credentials, which stay server-side by construction.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field


def _int(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, str(default)))
    except ValueError:
        return default


def _csv(name: str, default: str) -> list[str]:
    return [x.strip() for x in os.getenv(name, default).split(",") if x.strip()]


@dataclass(frozen=True)
class Config:
    base_path: str = os.getenv("DATARAFT_BASE_PATH", "/dataraft")

    minio_endpoint: str = os.getenv("MINIO_ENDPOINT", "minio.minio.svc.cluster.local:9000")
    minio_access_key: str = os.getenv("MINIO_ACCESS_KEY", "")
    minio_secret_key: str = os.getenv("MINIO_SECRET_KEY", "")
    minio_secure: bool = os.getenv("MINIO_SECURE", "false").lower() == "true"

    # only these buckets/prefixes are browsable; nothing else is reachable
    allowed_buckets: list[str] = field(
        default_factory=lambda: _csv("DATARAFT_ALLOWED_BUCKETS", "stable-stock,model-results"))

    default_limit: int = _int("DATARAFT_DEFAULT_LIMIT", 500)
    max_result_rows: int = _int("DATARAFT_MAX_RESULT_ROWS", 10_000)
    max_running_queries: int = _int("DATARAFT_MAX_RUNNING_QUERIES", 4)
    timeout_seconds: int = _int("DATARAFT_TIMEOUT_SECONDS", 60)
    memory_limit: str = os.getenv("DATARAFT_MEMORY_LIMIT", "4GB")
    temp_dir: str = os.getenv("DATARAFT_TEMP_DIR", "/tmp/dataraft-duckdb")


config = Config()
