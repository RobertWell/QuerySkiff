package com.queryskiff.registrar

import com.queryskiff.datasets.Datasets
import com.queryskiff.engine.TrinoEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * HEL-95: port of the Python `test_trino_engine.py` registrar/engine units —
 * the HEL-113 security boundary (parquet footers are attacker-controllable and
 * are interpolated into DDL) plus table naming and CTE-prelude generation.
 * No live Trino needed.
 */
class RegistrarTest {

    private fun ds(id: String) = Datasets.Dataset(
        datasetId = id, bucket = "b", key = "k.parquet", isFolder = false)

    private fun registrar(sniff: (Datasets.Dataset) -> List<Map<String, Any?>>) =
        Registrar(
            Registrar.Config("minio", "ds", "model-results", "queryskiff-tables/"),
            sniffer = sniff,
            connFactory = { throw IllegalStateException("no db in unit tests") },
            minioFactory = { throw IllegalStateException("no minio in unit tests") },
        )

    @Test
    fun `table name deterministic identifier-safe and python-parity`() {
        val a = Registrar.tableName(ds("id-one"))
        val b = Registrar.tableName(ds("id-two"))
        assertNotEquals(a, b)
        assertEquals(a, Registrar.tableName(ds("id-one")))
        assertTrue(a.startsWith("t_"))
        assertTrue(a.matches(Regex("^t_[0-9a-f]{16}$")))
        // golden value from the Python implementation:
        // hashlib.sha256(b"id-one").hexdigest()[:16] under the same naming rule
        assertEquals("t_34bd5b72432685ea", a)
    }

    @Test
    fun `type map core and decimal passthrough`() {
        assertEquals("bigint", Registrar.mapType("BIGINT"))
        assertEquals("varchar", Registrar.mapType("VARCHAR"))
        assertEquals("timestamp", Registrar.mapType("TIMESTAMP"))
        assertEquals("decimal(10,2)", Registrar.mapType("DECIMAL(10,2)"))
    }

    @Test
    fun `unknown type fails closed`() {
        assertThrows(Registrar.RegistrationError::class.java) { Registrar.mapType("GEOMETRY") }
    }

    @Test
    fun `decimal must match strict shape`() {
        assertEquals("decimal(38,0)", Registrar.mapType("DECIMAL(38,0)"))
        for (bad in listOf("DECIMAL(38,0) ) WITH (x", "DECIMAL(a,b)", "DECIMAL")) {
            assertThrows(Registrar.RegistrationError::class.java) { Registrar.mapType(bad) }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "x\" ) WITH (external_location='s3://evil/') --",
        "col; DROP TABLE t",
        "a b",
        "\" ",
        "1col",
    ])
    fun `malicious column name rejected`(evil: String) {
        // a crafted parquet footer must never reach DDL as an identifier
        assertThrows(Registrar.RegistrationError::class.java) { Registrar.safeColumn(evil) }
    }

    @Test
    fun `overlong column name rejected`() {
        assertThrows(Registrar.RegistrationError::class.java) {
            Registrar.safeColumn("x".repeat(129))
        }
    }

    @Test
    fun `sniff schema rejects injected column and duplicates`() {
        val injected = registrar { listOf(
            mapOf("column_name" to "x\") WITH (y", "column_type" to "BIGINT")) }
        assertThrows(Registrar.RegistrationError::class.java) { injected.sniffSchema(ds("id")) }

        val dup = registrar { listOf(
            mapOf("column_name" to "a", "column_type" to "BIGINT"),
            mapOf("column_name" to "A", "column_type" to "VARCHAR")) }
        assertThrows(Registrar.RegistrationError::class.java) { dup.sniffSchema(ds("id")) }

        val empty = registrar { emptyList() }
        assertThrows(Registrar.RegistrationError::class.java) { empty.sniffSchema(ds("id")) }
    }

    @Test
    fun `sniff schema maps valid footer`() {
        val ok = registrar { listOf(
            mapOf("column_name" to "stock_code", "column_type" to "VARCHAR"),
            mapOf("column_name" to "close_price", "column_type" to "DOUBLE")) }
        assertEquals(listOf("stock_code" to "varchar", "close_price" to "double"),
                     ok.sniffSchema(ds("id")))
    }

    @Test
    fun `safe location rejects quote injection`() {
        assertThrows(Registrar.RegistrationError::class.java) {
            Registrar.safeLocation("s3://b/k' ) WITH (format='CSV")
        }
        assertEquals("s3://model-results/queryskiff-tables/t_x/",
                     Registrar.safeLocation("s3://model-results/queryskiff-tables/t_x/"))
    }

    @Test
    fun `wrap builds cte prelude only from server names`() {
        val sql = TrinoEngine.wrap(
            "SELECT a.x FROM a JOIN b ON a.i = b.i LIMIT 5",
            listOf("a" to "t_aaaa", "b" to "t_bbbb"), "minio", "ds")
        assertTrue(sql.startsWith("WITH \"a\" AS (SELECT * FROM "))
        assertTrue(sql.contains(".t_aaaa)"))
        assertTrue(sql.contains(".t_bbbb)"))
        assertTrue(sql.endsWith("SELECT a.x FROM a JOIN b ON a.i = b.i LIMIT 5"))
    }

    @Test
    fun `wrapped sql parses`() {
        val sql = TrinoEngine.wrap(
            "SELECT count(*) c FROM rows JOIN prices ON rows.s = prices.s",
            listOf("rows" to "t_1", "prices" to "t_2"), "minio", "ds")
        // JSQLParser stands in for sqlglot's trino parse in the Python test
        net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(sql)
    }
}
