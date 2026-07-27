package com.queryskiff.sql

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse

/**
 * HEL-95 parser-parity spike: the EXACT corpora from the Python contract
 * (backend/tests/contract + test_sqlsafety). Every divergence found here is a
 * migration decision to record — before any Quarkus investment.
 */
class SqlPolicyParityTest {

    // ── must ALLOW (from test_allows_read_only) ──────────────────────────────
    @ParameterizedTest
    @ValueSource(strings = [
        "SELECT * FROM data LIMIT 500",
        "SELECT symbol, score FROM data WHERE score > 0.6 ORDER BY score DESC LIMIT 10",
        "WITH t AS (SELECT * FROM data) SELECT count(*) FROM t",
        "SELECT symbol, avg(score) FROM data GROUP BY symbol HAVING avg(score) > 0.5",
        "SELECT *, row_number() OVER (ORDER BY score) FROM data",
        "EXPLAIN SELECT * FROM data",
        "DESCRIBE data",
    ])
    fun allowsReadOnly(sql: String) {
        SqlPolicy.validate(sql)
    }

    // ── must REJECT (from the contract UNSAFE_CORPUS) ────────────────────────
    @ParameterizedTest
    @ValueSource(strings = [
        "INSERT INTO data VALUES (1)",
        "UPDATE data SET x=1",
        "DELETE FROM data",
        "DROP TABLE data",
        "CREATE TABLE x AS SELECT 1",
        "COPY data TO 's3://x/y'",
        "ATTACH 'x.db'",
        "INSTALL httpfs",
        "LOAD httpfs",
        "PRAGMA database_list",
        "SET memory_limit='1GB'",
        "SELECT * FROM read_parquet('s3://secret/x.parquet')",
        "SELECT * FROM data; DROP TABLE data",
        "SELECT * FROM data; SELECT * FROM data",
        "SELECT * FROM other_table",
        "SELECT * FROM read_csv('/etc/passwd')",
        "SELECT * FROM data WHERE x IN (SELECT * FROM read_parquet('s3://a/b'))",
        "SELECT * FROM data -- \n; DROP TABLE data",
        "  sElEcT * FROM data; InSeRt INTO data VALUES (1) ",
        "   ",
    ])
    fun rejectsUnsafe(sql: String) {
        assertThrows<UnsafeSql> { SqlPolicy.validate(sql) }
    }

    // ── has_limit parity ─────────────────────────────────────────────────────
    @Test
    fun hasLimitParity() {
        assertTrue(SqlPolicy.hasLimit("SELECT * FROM data LIMIT 10"))
        assertFalse(SqlPolicy.hasLimit("SELECT * FROM data"))
        assertFalse(SqlPolicy.hasLimit("SELECT count(*) FROM data"))
    }
}
