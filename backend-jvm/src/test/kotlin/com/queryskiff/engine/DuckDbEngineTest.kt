package com.queryskiff.engine

import com.queryskiff.datasets.Datasets
import com.queryskiff.sql.UnsafeSql
import com.queryskiff.workspace.Workspace
import java.nio.file.Files
import java.sql.DriverManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * HEL-95 phase 4: the bounded DuckDB engine + workspace against REAL parquet
 * fixtures (generated in-test with DuckDB itself, mirroring the Python
 * contract harness: rows.parquet 200 rows / prices.parquet 7 rows, joinable on
 * `symbol`). The uriRewriter seam maps the server-side s3 URIs to the local
 * fixtures — resolveId/SqlPolicy/engine all run REAL.
 */
class DuckDbEngineTest {

    companion object {
        private lateinit var rowsPath: String
        private lateinit var pricesPath: String
        private const val BUCKET = "contractbkt"
        private val ROWS_DS = Datasets.resolveId(
            Datasets.encodeId(BUCKET, "fixture/rows.parquet"), listOf(BUCKET))
        private val PRICES_DS = Datasets.resolveId(
            Datasets.encodeId(BUCKET, "fixture/prices.parquet"), listOf(BUCKET))

        @JvmStatic
        @BeforeAll
        fun fixtures() {
            val dir = Files.createTempDirectory("qs-fixture")
            rowsPath = dir.resolve("rows.parquet").toString()
            pricesPath = dir.resolve("prices.parquet").toString()
            DriverManager.getConnection("jdbc:duckdb:").use { c ->
                c.createStatement().use { st ->
                    st.execute("COPY (SELECT i AS id, 'sym' || (i % 7) AS symbol, i * 0.5 AS score " +
                               "FROM range(200) t(i)) TO '$rowsPath' (FORMAT parquet)")
                    st.execute("COPY (SELECT 'sym' || i AS symbol, 100.0 + i AS price " +
                               "FROM range(7) t(i)) TO '$pricesPath' (FORMAT parquet)")
                }
            }
        }
    }

    private fun engine(maxRows: Int = 100, defaultLimit: Int = 50, timeout: Int = 20) =
        DuckDbEngine(DuckDbEngine.EngineConfig(
            minioEndpoint = "localhost:9", minioAccessKey = "x", minioSecretKey = "y",
            defaultLimit = defaultLimit, maxResultRows = maxRows, timeoutSeconds = timeout,
            allowedBuckets = listOf(BUCKET),
            uriRewriter = { uri ->
                when {
                    uri.endsWith("rows.parquet") -> rowsPath
                    uri.endsWith("prices.parquet") -> pricesPath
                    else -> uri
                }
            }))

    private fun await(e: DuckDbEngine, q: DuckDbEngine.Query): DuckDbEngine.Query {
        val deadline = System.currentTimeMillis() + 30_000
        while (q.status in setOf("pending", "running")) {
            check(System.currentTimeMillis() < deadline) { "query did not settle" }
            Thread.sleep(20)
        }
        return q
    }

    @Test
    fun `single dataset legacy data flow`() {
        val e = engine()
        val q = await(e, e.createQuery(listOf(ROWS_DS to "data"), "SELECT count(*) c FROM data"))
        assertEquals("done", q.status, q.error ?: "")
        assertEquals(listOf("c"), q.columns)
        assertEquals(200L, q.rows[0][0])
    }

    @Test
    fun `workspace join across aliases`() {
        val e = engine()
        val q = await(e, e.createQuery(
            listOf(ROWS_DS to "rows", PRICES_DS to "prices"),
            "SELECT rows.symbol, count(*) n, max(prices.price) p " +
            "FROM rows JOIN prices ON rows.symbol = prices.symbol " +
            "GROUP BY rows.symbol ORDER BY rows.symbol LIMIT 10"))
        assertEquals("done", q.status, q.error ?: "")
        assertEquals(7, q.rowCount)                     // 7 symbols
        assertEquals(listOf("symbol", "n", "p"), q.columns)
    }

    @Test
    fun `unregistered table rejected before execution`() {
        val e = engine()
        assertThrows<UnsafeSql> {
            e.createQuery(listOf(ROWS_DS to "rows"), "SELECT * FROM prices LIMIT 5")
        }
    }

    @Test
    fun `default limit injected when absent and truncation flag at cap`() {
        val e = engine(maxRows = 100, defaultLimit = 50)
        val q1 = await(e, e.createQuery(listOf(ROWS_DS to "data"), "SELECT * FROM data"))
        assertEquals(50, q1.rowCount)                   // injected LIMIT 50
        assertFalse(q1.truncated)                       // injected limit is NOT truncation
        val q2 = await(e, e.createQuery(listOf(ROWS_DS to "data"),
                                        "SELECT * FROM data LIMIT 200"))
        assertEquals(100, q2.rowCount)                  // capped at maxResultRows
        assertTrue(q2.truncated)
    }

    @Test
    fun `cancellation settles the query`() {
        val e = engine()
        val q = e.createQuery(
            listOf(ROWS_DS to "a", PRICES_DS to "b"),
            "SELECT count(*) FROM a, a a2, a a3, b LIMIT 1")
        e.cancelQuery(q.id)
        await(e, q)
        assertTrue(q.status in setOf("cancelled", "done"))  // tiny fixture may win the race
    }

    @Test
    fun `runtime sql error is redacted status not a crash`() {
        val e = engine()
        val q = await(e, e.createQuery(listOf(ROWS_DS to "data"),
                                       "SELECT no_such_column FROM data"))
        assertEquals("error", q.status)
        assertFalse(q.error!!.contains("s3://"))
        assertFalse(q.error!!.contains(BUCKET))
    }

    @Test
    fun `workspace resolve plus engine end to end with hints`() {
        val entries = Workspace.resolveEntries(
            listOf(mapOf("dataset_id" to ROWS_DS.datasetId, "alias" to "rows"),
                   mapOf("dataset_id" to PRICES_DS.datasetId, "alias" to "prices")),
            listOf(BUCKET))
        val e = engine()
        val schemas = entries.associate { it.alias to e.schemaOf(it.dataset) }
        val hints = Workspace.joinHints(schemas)
        val symbol = hints.first { it.column == "symbol" }
        assertTrue(symbol.compatible)
        val starter = Workspace.starterJoinSql(entries, hints)
        assertTrue("JOIN prices ON rows.symbol = prices.symbol" in starter)
        val q = await(e, e.createQuery(entries.map { it.dataset to it.alias }, starter))
        assertEquals("done", q.status, q.error ?: "")
    }
}
