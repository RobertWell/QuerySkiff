package com.queryskiff.registrar

import com.queryskiff.datasets.Datasets
import io.minio.CopyObjectArgs
import io.minio.CopySource
import io.minio.ListObjectsArgs
import io.minio.MinioClient
import io.minio.StatObjectArgs
import java.security.MessageDigest
import java.sql.Connection

/**
 * HEL-95: JVM port of `queryskiff.registrar` — invisible auto-registration of
 * datasets as Trino tables (HEL-113 design). Parquet is self-describing, so a
 * dataset becomes a Trino table with NO data scan:
 *
 *   1. layout   — a loose object is server-side-copied into its own managed
 *                 prefix (Hive external tables bind to a directory); a folder
 *                 dataset already IS a directory and is registered in place.
 *   2. sniff    — schema read from the parquet footer (DuckDB DESCRIBE over
 *                 the object: reads footer + metadata only).
 *   3. register — idempotent CREATE TABLE IF NOT EXISTS named by a hash of the
 *                 opaque dataset id, in the shared catalog schema.
 *
 * Registrations are GLOBAL and shared by all users (one table per dataset
 * version); a source etag change re-materializes and re-registers. The browser
 * never sees table names, prefixes, or DDL.
 *
 * SECURITY (same boundary as the Python module): parquet footers are attacker-
 * controllable — anyone who can land a file in an allowed bucket controls the
 * column names/types — and Trino DDL has no bound-parameter form for
 * identifiers/types, so strict allowlist validation here is the only safe gate.
 */
class Registrar(
    private val cfg: Config,
    /** schema sniffer — the DuckDB engine's schemaOf (footer read only). */
    private val sniffer: (Datasets.Dataset) -> List<Map<String, Any?>>,
    /** Trino connection factory (caller closes). */
    private val connFactory: () -> Connection,
    private val minioFactory: () -> MinioClient,
) {

    data class Config(
        val catalog: String,
        val schema: String,
        val managedBucket: String,
        val managedPrefix: String,
    )

    class RegistrationError(message: String) : RuntimeException(message)

    // dataset_id -> (source etag, table name); in-memory: re-sniffing after a
    // pod restart costs one footer read + idempotent DDL, so no persistence.
    private val registered = HashMap<String, Pair<String, String>>()
    private val lock = Any()

    companion object {
        private val SAFE_COLUMN = Regex("^[A-Za-z_][A-Za-z0-9_]{0,127}$")
        private val DECIMAL = Regex("^DECIMAL\\(\\d{1,2},\\d{1,2}\\)$")
        private val SAFE_LOCATION = Regex("^s3://[A-Za-z0-9._\\-/]+$")

        // DuckDB DESCRIBE type -> Trino DDL type. Unknown types fail
        // registration loudly rather than guessing (fail-closed; the DuckDB
        // engine path remains available for such files).
        private val TYPE_MAP = mapOf(
            "BOOLEAN" to "boolean", "TINYINT" to "tinyint", "SMALLINT" to "smallint",
            "INTEGER" to "integer", "BIGINT" to "bigint", "HUGEINT" to "decimal(38,0)",
            "UTINYINT" to "smallint", "USMALLINT" to "integer", "UINTEGER" to "bigint",
            "UBIGINT" to "decimal(20,0)",
            "FLOAT" to "real", "DOUBLE" to "double",
            "VARCHAR" to "varchar", "BLOB" to "varbinary",
            "DATE" to "date", "TIME" to "time",
            "TIMESTAMP" to "timestamp",
            "TIMESTAMP WITH TIME ZONE" to "timestamp with time zone",
        )

        fun tableName(ds: Datasets.Dataset): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(ds.datasetId.toByteArray(Charsets.UTF_8))
            return "t_" + digest.joinToString("") { "%02x".format(it) }.substring(0, 16)
        }

        fun mapType(duckType: String): String {
            val t = duckType.trim().uppercase()
            if (t.startsWith("DECIMAL")) {
                // strict shape only — never pass an arbitrary string to DDL
                if (!DECIMAL.matches(t)) throw RegistrationError("unsupported decimal type: $duckType")
                return t.lowercase()
            }
            return TYPE_MAP[t]
                ?: throw RegistrationError("unsupported column type for registration: $duckType")
        }

        fun safeColumn(name: String): String {
            // do not echo the raw name into the error (it reaches the browser)
            if (!SAFE_COLUMN.matches(name))
                throw RegistrationError("dataset has a column name that cannot be registered")
            return name
        }

        /** external_location interpolates into a single-quoted DDL string.
         *  ds.key is SAFE_KEY-validated at resolveId time (no quotes possible)
         *  and managed paths use a server-generated hash — assert that
         *  invariant and double any quote as belt-and-braces. */
        fun safeLocation(loc: String): String {
            if (!SAFE_LOCATION.matches(loc)) throw RegistrationError("dataset path is not registrable")
            return loc.replace("'", "''")
        }
    }

    fun sniffSchema(ds: Datasets.Dataset): List<Pair<String, String>> {
        val rows = sniffer(ds)
        val out = mutableListOf<Pair<String, String>>()
        val seen = HashSet<String>()
        for (r in rows) {
            val col = safeColumn(r["column_name"].toString())
            if (!seen.add(col.lowercase()))
                throw RegistrationError("dataset has duplicate column names")
            out += col to mapType(r["column_type"].toString())
        }
        if (out.isEmpty()) throw RegistrationError("dataset has no columns")
        return out
    }

    private fun sourceEtag(ds: Datasets.Dataset): String {
        val client = minioFactory()
        if (ds.isFolder) {
            // folder datasets register in place; version by the part listing
            val parts = client.listObjects(
                ListObjectsArgs.builder().bucket(ds.bucket).prefix(ds.key).recursive(true).build())
                .map { it.get() }
                .filter { it.objectName().lowercase().endsWith(".parquet") }
                .map { it.objectName() to it.etag() }
                .sortedBy { it.first }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(parts.toString().toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.substring(0, 16)
        }
        return client.statObject(
            StatObjectArgs.builder().bucket(ds.bucket).`object`(ds.key).build())
            .etag() ?: "unknown"
    }

    /** Ensure the dataset lives in a directory Trino can bind to; return the
     *  external_location. Loose file -> server-side copy into the managed
     *  prefix (no data download); folder dataset -> its own prefix, in place. */
    private fun materialize(ds: Datasets.Dataset, tname: String): String {
        if (ds.isFolder) return safeLocation("s3://${ds.bucket}/${ds.key}")
        val dstKey = "${cfg.managedPrefix}$tname/part-0.parquet"
        minioFactory().copyObject(
            CopyObjectArgs.builder()
                .bucket(cfg.managedBucket).`object`(dstKey)
                .source(CopySource.builder().bucket(ds.bucket).`object`(ds.key).build())
                .build())
        return safeLocation("s3://${cfg.managedBucket}/${cfg.managedPrefix}$tname/")
    }

    /** Idempotent: returns the Trino table name for the dataset, registering
     *  (and re-registering on source change) as needed. Thread-safe. */
    fun ensureRegistered(ds: Datasets.Dataset): String {
        val etag = sourceEtag(ds)
        val cached: Pair<String, String>?
        synchronized(lock) {
            cached = registered[ds.datasetId]
            if (cached != null && cached.first == etag) return cached.second
        }
        val tname = tableName(ds)
        val cols = sniffSchema(ds)
        val location = materialize(ds, tname)
        val ddlCols = cols.joinToString(", ") { (c, t) -> "\"$c\" $t" }
        connFactory().use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    "CREATE SCHEMA IF NOT EXISTS ${cfg.catalog}.${cfg.schema} " +
                    "WITH (location = 's3://${cfg.managedBucket}/${cfg.managedPrefix}')")
                val full = "${cfg.catalog}.${cfg.schema}.$tname"
                if (cached != null) {  // source changed: drop the stale registration
                    st.execute("DROP TABLE IF EXISTS $full")
                }
                st.execute(
                    "CREATE TABLE IF NOT EXISTS $full ($ddlCols) " +
                    "WITH (external_location = '$location', format = 'PARQUET')")
            }
        }
        synchronized(lock) { registered[ds.datasetId] = etag to tname }
        return tname
    }
}
