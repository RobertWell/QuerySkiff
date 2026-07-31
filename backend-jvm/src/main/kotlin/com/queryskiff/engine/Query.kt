package com.queryskiff.engine

import java.sql.Statement
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared query state, mirrored from the Python `engine.Query` dataclass (the
 * Trino engine imports it from the DuckDB module there; here both engines use
 * this one class). Fields are @Volatile because a poller thread reads them
 * while the runner thread writes; `status` is the publication signal, so
 * writers must set every other field (especially `error`) BEFORE status.
 */
class Query internal constructor(val id: String, val sql: String,
                                 val datasetIds: List<String>, val aliases: List<String>) {
    @Volatile var status: String = "pending"   // pending|running|done|error|cancelled
    @Volatile var error: String? = null
    @Volatile var columns: List<String> = emptyList()
    @Volatile var rows: List<List<Any?>> = emptyList()
    @Volatile var rowCount: Int = 0
    @Volatile var truncated: Boolean = false
    internal val cancelFlag = AtomicBoolean(false)
    @Volatile internal var activeStatement: Statement? = null
}

/** The engine surface ApiResource talks to; selected by QUERYSKIFF_ENGINE. */
interface QueryEngine {
    fun createQuery(entries: List<Pair<com.queryskiff.datasets.Datasets.Dataset, String>>,
                    sql: String): Query
    fun getQuery(id: String): Query?
    fun cancelQuery(id: String): Boolean
    fun schemaOf(ds: com.queryskiff.datasets.Datasets.Dataset): List<Map<String, Any?>>
}
