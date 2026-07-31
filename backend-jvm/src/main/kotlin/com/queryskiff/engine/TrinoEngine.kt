package com.queryskiff.engine

import com.queryskiff.datasets.Datasets
import com.queryskiff.registrar.Registrar
import com.queryskiff.sql.SqlPolicy
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * HEL-95: JVM port of `queryskiff.engine_trino` — bounded Trino query engine
 * (HEL-112/113), same public surface as the DuckDB engine, selected by
 * QUERYSKIFF_ENGINE=trino.
 *
 * Workspace aliases are mapped onto auto-registered tables with a CTE prelude:
 *
 *     WITH "alias1" AS (SELECT * FROM cat.sch.t_x), ... <user sql>
 *
 * CTEs shadow table resolution, are inlined by the optimizer (pushdown intact),
 * require no per-query DDL, and keep the user's SQL referencing ONLY validated
 * alias names — catalog/schema/table names never appear in user-visible text.
 *
 * Bounds: shared semaphore sized like the Python path (Trino's own resource
 * governance is the real limiter — the semaphore just caps this pod's
 * in-flight requests), per-session query_max_run_time (JDBC sessionProperties),
 * capped result rows, Statement.cancel(). Errors are redacted before reaching
 * the browser, and written BEFORE status (the publication signal).
 */
class TrinoEngine(private val config: Config, private val registrar: Registrar) : QueryEngine {

    data class Config(
        val host: String,
        val port: Int,
        val catalog: String,
        val schema: String,
        val defaultLimit: Int = 500,
        val maxResultRows: Int = 10_000,
        val maxRunningQueries: Int = 16,
        val timeoutSeconds: Int = 60,
        val allowedBuckets: List<String> = emptyList(),
    )

    private val semaphore = Semaphore(config.maxRunningQueries)
    private val queries = ConcurrentHashMap<String, Query>()
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "qs-trino-query").apply { isDaemon = true }
    }

    fun connect(user: String = "queryskiff"): Connection = openConnection(config, user)

    companion object {
        /** Companion so the Registrar's connection factory can be wired before
         *  the engine instance exists (they reference each other). */
        fun openConnection(config: Config, user: String = "queryskiff"): Connection {
            val props = Properties().apply {
                setProperty("user", user)
                setProperty("sessionProperties",
                            "query_max_run_time:${config.timeoutSeconds}s")
            }
            return DriverManager.getConnection(
                "jdbc:trino://${config.host}:${config.port}/${config.catalog}/${config.schema}",
                props)
        }

        /** Prepend the alias->table CTE prelude. Alias names are pre-validated
         *  identifiers; table names are server-generated (t_<hash>). */
        fun wrap(sql: String, tables: List<Pair<String, String>>,
                 catalog: String, schema: String): String {
            val ctes = tables.joinToString(", ") { (alias, t) ->
                "\"$alias\" AS (SELECT * FROM $catalog.$schema.$t)"
            }
            return "WITH $ctes $sql"
        }
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
            val tables = entries.map { (ds, alias) -> alias to registrar.ensureRegistered(ds) }
            connect().use { conn ->
                conn.createStatement().use { st ->
                    q.activeStatement = st
                    try {
                        val rs = st.executeQuery(
                            wrap(q.sql, tables, config.catalog, config.schema))
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
                        q.activeStatement = null
                    }
                }
            }
        } catch (e: Exception) {
            if (q.cancelFlag.get()) {
                q.status = "cancelled"
            } else {
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

    /** Schema via the registered table (registers on first touch). Shape
     *  mirrors the DuckDB DESCRIBE dicts the API already exposes. */
    override fun schemaOf(ds: Datasets.Dataset): List<Map<String, Any?>> {
        val tname = registrar.ensureRegistered(ds)
        connect().use { conn ->
            conn.createStatement().use { st ->
                val rs = st.executeQuery("DESCRIBE ${config.catalog}.${config.schema}.$tname")
                return buildList {
                    while (rs.next()) add(mapOf(
                        "column_name" to rs.getString(1), "column_type" to rs.getString(2),
                        "null" to "YES", "key" to null, "default" to null, "extra" to null))
                }
            }
        }
    }
}
