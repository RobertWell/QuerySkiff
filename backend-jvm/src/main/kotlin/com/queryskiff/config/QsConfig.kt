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

    val defaultLimit: Int get() = envInt("QUERYSKIFF_DEFAULT_LIMIT", 500)
    val maxResultRows: Int get() = envInt("QUERYSKIFF_MAX_RESULT_ROWS", 10_000)
    val maxRunningQueries: Int
        get() = envInt("QUERYSKIFF_MAX_RUNNING_QUERIES", 4)
    val timeoutSeconds: Int get() = envInt("QUERYSKIFF_TIMEOUT_SECONDS", 60)
    val memoryLimit: String get() = env("QUERYSKIFF_MEMORY_LIMIT", "4GB")
    val tempDir: String get() = env("QUERYSKIFF_TEMP_DIR", "/tmp/queryskiff-duckdb")
}
