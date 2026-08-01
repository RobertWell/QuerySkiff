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

    /** Parse a human byte size ("256MB", "2GB", "512K", or a plain byte count).
     *  Binary units (1KB = 1024). Falls back to the default expression's value
     *  by throwing on nonsense — callers pass a valid default string. */
    internal fun parseBytes(raw: String): Long {
        val s = raw.trim().uppercase()
        val m = Regex("^(\\d+)\\s*(B|KB|MB|GB|TB|K|M|G|T)?$").matchEntire(s)
            ?: throw IllegalArgumentException("invalid byte size: $raw")
        val n = m.groupValues[1].toLong()
        val mult = when (m.groupValues[2]) {
            "", "B" -> 1L
            "K", "KB" -> 1L shl 10
            "M", "MB" -> 1L shl 20
            "G", "GB" -> 1L shl 30
            "T", "TB" -> 1L shl 40
            else -> 1L
        }
        return n * mult
    }

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

    // HEL-121 virtual-dataset registry (records live in MinIO, keyed by id)
    val registryBucket: String get() = env("QUERYSKIFF_REGISTRY_BUCKET", "model-results")
    val registryPrefix: String get() = env("QUERYSKIFF_REGISTRY_PREFIX", "queryskiff-virtual/")
    val virtualWarnFiles: Int get() = envInt("QUERYSKIFF_VIRTUAL_WARN_FILES", 64)
    val virtualMaxFiles: Int get() = envInt("QUERYSKIFF_VIRTUAL_MAX_FILES", 512)
    // HEL-121: input-BYTE budget for a saved virtual selection — distinct from
    // file count, result-row count, and DuckDB memory. Total on-disk size of the
    // member objects; crossing warn attaches a compaction/promotion hint, the
    // hard cap rejects the save. Human sizes ("256MB", "2GB", "512K", bytes).
    val virtualWarnBytes: Long get() = parseBytes(env("QUERYSKIFF_VIRTUAL_WARN_BYTES", "256MB"))
    val virtualMaxBytes: Long get() = parseBytes(env("QUERYSKIFF_VIRTUAL_MAX_BYTES", "2GB"))

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
