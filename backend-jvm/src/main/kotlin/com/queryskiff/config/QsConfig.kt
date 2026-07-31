package com.queryskiff.config

import jakarta.enterprise.context.ApplicationScoped

/**
 * HEL-95: env-driven configuration mirroring `queryskiff.config` (canonical
 * QUERYSKIFF_* names; MINIO_* for storage credentials — injected by the
 * platform, never committed, never sent to the browser).
 *
 * Divergence note: the Python module honors a DATARAFT_* fallback for the
 * HEL-98 transition window; HEL-118 removes it, so the port ships without it
 * (one namespace, not two — approved migration note).
 */
@ApplicationScoped
class QsConfig {
    private fun env(name: String, default: String): String =
        System.getenv(name) ?: default

    private fun envInt(name: String, default: Int): Int =
        System.getenv(name)?.toIntOrNull() ?: default

    val basePath: String get() = env("QUERYSKIFF_BASE_PATH", "/queryskiff").trimEnd('/')

    val minioEndpoint: String get() = env("MINIO_ENDPOINT", "minio.minio.svc.cluster.local:9000")
    val minioAccessKey: String get() = env("MINIO_ACCESS_KEY", "")
    val minioSecretKey: String get() = env("MINIO_SECRET_KEY", "")
    val minioSecure: Boolean get() = env("MINIO_SECURE", "false").lowercase() == "true"

    val allowedBuckets: List<String>
        get() = env("QUERYSKIFF_ALLOWED_BUCKETS", "stable-stock,model-results")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    // HEL-112/113: query engine. "duckdb" (default, per-query embedded) or
    // "trino" (shared engine + auto-registration). Rollback = flip the flag.
    val engine: String get() = env("QUERYSKIFF_ENGINE", "duckdb")
    val trinoHost: String get() = env("QUERYSKIFF_TRINO_HOST", "trino.trino.svc.cluster.local")
    val trinoPort: Int get() = envInt("QUERYSKIFF_TRINO_PORT", 8080)
    val trinoCatalog: String get() = env("QUERYSKIFF_TRINO_CATALOG", "minio")
    val trinoSchema: String get() = env("QUERYSKIFF_TRINO_SCHEMA", "ds")
    // managed per-table prefixes for auto-registration (server-side copies of
    // loose parquet objects live here; bucket must be in allowed_buckets' MinIO)
    val trinoManagedBucket: String get() = env("QUERYSKIFF_TRINO_MANAGED_BUCKET", "model-results")
    val trinoManagedPrefix: String get() = env("QUERYSKIFF_TRINO_MANAGED_PREFIX", "queryskiff-tables/")

    val defaultLimit: Int get() = envInt("QUERYSKIFF_DEFAULT_LIMIT", 500)
    val maxResultRows: Int get() = envInt("QUERYSKIFF_MAX_RESULT_ROWS", 10_000)
    // HEL-112 multi-user: DuckDB's low cap protects per-process memory; under
    // Trino the engine governs memory/queueing globally, so this pod can admit
    // far more in-flight requests (override explicitly to tune either way).
    val maxRunningQueries: Int
        get() = envInt("QUERYSKIFF_MAX_RUNNING_QUERIES", if (engine == "trino") 16 else 4)
    val timeoutSeconds: Int get() = envInt("QUERYSKIFF_TIMEOUT_SECONDS", 60)
    val memoryLimit: String get() = env("QUERYSKIFF_MEMORY_LIMIT", "4GB")
    val tempDir: String get() = env("QUERYSKIFF_TEMP_DIR", "/tmp/queryskiff-duckdb")
}
