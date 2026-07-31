package com.queryskiff.engine

import com.queryskiff.datasets.Datasets
import com.queryskiff.sql.SqlPolicy
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * HEL-95 phase 4: JVM port of `queryskiff.engine` — the bounded per-query
 * DuckDB engine with the HEL-112 N-view workspace model. Every query runs in
 * its own in-memory DuckDB connection with each dataset exposed ONLY under its
 * validated alias. Bounds mirror the Python contract: global concurrency
 * semaphore, per-query timeout (statement cancel), capped result rows with a
 * `truncated` flag, memory limit, temp spill dir, and cancellation.
 *
 * View creation parity note: Python binds the parquet path through the native
 * relation API (never raw SQL interpolation). JDBC has no relation API and
 * DuckDB refuses prepared parameters in DDL ("this type of statement can't be
 * prepared"), so views interpolate the URI into a single-quoted literal —
 * guarded by the same invariant chain: the URI is server-constructed from
 * `resolveId`-validated bucket+key (SAFE_KEY forbids quotes/backslashes/
 * control chars), re-asserted here against a strict allowlist, and
 * quote-escaped as belt-and-braces. Aliases are pre-validated identifiers
 * (Workspace) quoted defensively.
 */
class DuckDbEngine(private val config: EngineConfig) : QueryEngine {

    data class EngineConfig(
        val minioEndpoint: String,
        val minioAccessKey: String,
        val minioSecretKey: String,
        val minioSecure: Boolean = false,
        val defaultLimit: Int = 500,
        val maxResultRows: Int = 10_000,
        val maxRunningQueries: Int = 4,
        val timeoutSeconds: Int = 60,
        val memoryLimit: String = "4GB",
        val tempDir: String = "/tmp/queryskiff-duckdb",
        val allowedBuckets: List<String> = emptyList(),
        /** test seam: rewrite an s3 URI to a local fixture path (contract harness). */
        val uriRewriter: (String) -> String = { it },
    )

    private val semaphore = Semaphore(config.maxRunningQueries)
    private val queries = ConcurrentHashMap<String, Query>()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "qs-query").apply { isDaemon = true }
    }
    private val timers = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "qs-timeout").apply { isDaemon = true }
    }

    private fun newConnection(entries: List<Pair<Datasets.Dataset, String>>): Connection {
        val conn = DriverManager.getConnection("jdbc:duckdb:")
        conn.createStatement().use { st ->
            st.execute("SET memory_limit='${config.memoryLimit}'")
            st.execute("SET temp_directory='${config.tempDir}'")
            st.execute("INSTALL httpfs")
            st.execute("LOAD httpfs")
            st.execute("SET s3_endpoint='${config.minioEndpoint}'")
            st.execute("SET s3_use_ssl=${config.minioSecure}")
            st.execute("SET s3_url_style='path'")
            st.execute("SET s3_access_key_id='${config.minioAccessKey}'")
            st.execute("SET s3_secret_access_key='${config.minioSecretKey}'")
        }
        // HEL-121: entries sharing one alias form a SINGLE multi-file view —
        // a saved virtual selection queried as one logical source. Schema
        // combination is explicit: strict by default; union_by_name when the
        // selection's policy says so. Incompatible files fail with DuckDB's
        // schema diagnostic (never silently coerced).
        for ((alias, group) in entries.groupBy({ it.second }, { it.first })) {
            // belt-and-braces (aliases are Workspace-validated upstream): an
            // identifier that could escape its quotes must never reach DDL.
            require(!alias.contains('"') && !alias.contains('\\')) { "invalid alias" }
            val uris = group.map { safeUri(config.uriRewriter(Datasets.s3Uri(it))) }
            val list = uris.joinToString(", ") { "'" + it + "'" }
            val union = if (group.any { it.unionByName }) ", union_by_name=true" else ""
            conn.createStatement().use { st ->
                st.execute("CREATE OR REPLACE VIEW \"$alias\" AS SELECT * FROM read_parquet([$list]$union)")
            }
        }
        return conn
    }

    private val SAFE_URI = Regex("^[A-Za-z0-9._\\-/:*]+$")

    /** The URI is server-built from validated parts; assert that invariant at
     *  the interpolation site and escape quotes as defence in depth. */
    private fun safeUri(uri: String): String {
        require(SAFE_URI.matches(uri)) { "dataset path is not queryable" }
        return uri.replace("'", "''")
    }

    override fun createQuery(entries: List<Pair<Datasets.Dataset, String>>, sql: String): Query {
        val aliases = entries.map { it.second }.toSet()
        var safe = SqlPolicy.validate(sql, allowedTables = aliases)
        if (!SqlPolicy.hasLimit(safe)) {
            safe = "SELECT * FROM ($safe) AS _sub LIMIT ${config.defaultLimit}"
        }
        val q = Query(UUID.randomUUID().toString().replace("-", ""), safe,
                      entries.map { it.first.datasetId }, entries.map { it.second })
        queries[q.id] = q
        pool.execute { run(q, entries) }
        return q
    }

    private fun run(q: Query, entries: List<Pair<Datasets.Dataset, String>>) {
        if (!semaphore.tryAcquire(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)) {
            q.error = "server busy (max concurrent queries)"; q.status = "error"
            return
        }
        try {
            if (q.cancelFlag.get()) { q.status = "cancelled"; return }
            q.status = "running"
            newConnection(entries).use { conn ->
                conn.createStatement().use { st ->
                    q.activeStatement = st
                    val timeout = timers.schedule(
                        { runCatching { st.cancel() } },
                        config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                    try {
                        val rs = st.executeQuery(q.sql)
                        val meta = rs.metaData
                        q.columns = (1..meta.columnCount).map { meta.getColumnLabel(it) }
                        val out = mutableListOf<List<Any?>>()
                        var overflow = false
                        while (rs.next()) {
                            if (out.size >= config.maxResultRows) { overflow = true; break }
                            out += (1..meta.columnCount).map { rs.getObject(it) }
                        }
                        q.rows = out
                        q.rowCount = out.size
                        q.truncated = overflow
                        q.status = if (q.cancelFlag.get()) "cancelled" else "done"
                    } finally {
                        timeout.cancel(false)
                        q.activeStatement = null
                    }
                }
            }
        } catch (e: Exception) {
            if (q.cancelFlag.get()) {
                q.status = "cancelled"
            } else {
                // write error BEFORE status: status is the publication signal
                // pollers key on, so it must be the last field to change.
                val first = (e.message ?: "query failed").lineSequence().first()
                q.error = Datasets.redact(first, config.allowedBuckets).take(500)
                q.status = "error"
            }
        } finally {
            semaphore.release()
        }
    }

    override fun getQuery(id: String): Query? = queries[id]

    override fun cancelQuery(id: String): Boolean {
        val q = queries[id] ?: return false
        if (q.status in setOf("done", "error", "cancelled")) return false
        q.cancelFlag.set(true)
        runCatching { q.activeStatement?.cancel() }
        return true
    }

    override fun schemaOf(ds: Datasets.Dataset): List<Map<String, Any?>> =
        newConnection(listOf(ds to "data")).use { conn ->
            conn.createStatement().use { st ->
                val rs = st.executeQuery("DESCRIBE SELECT * FROM data")
                val meta = rs.metaData
                val cols = (1..meta.columnCount).map { meta.getColumnLabel(it) }
                buildList {
                    while (rs.next()) add(cols.associateWith { c ->
                        rs.getObject(cols.indexOf(c) + 1)
                    })
                }
            }
        }
}
