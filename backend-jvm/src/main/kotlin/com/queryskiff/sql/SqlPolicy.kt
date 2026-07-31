package com.queryskiff.sql

import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.ExplainStatement
import net.sf.jsqlparser.statement.DescribeStatement
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.select.PlainSelect
import net.sf.jsqlparser.util.TablesNamesFinder

/** Raised for any statement outside the read-only policy (contract: HTTP 400
 *  with detail starting "unsafe SQL:"). */
class UnsafeSql(message: String) : Exception(message)

/**
 * HEL-95 spike: JVM port of `queryskiff.sqlsafety.validate`, matching its
 * LAYERED defence-in-depth exactly (not a single-mechanism rewrite) so the
 * contract corpus passes identically:
 *
 *   1. strip a leading EXPLAIN, then a banned-SUBSTRING scan on the lowered
 *      text — this is what rejects read_parquet / read_csv (via read_parquet? no
 *      -> both listed) / s3:// / /etc/ wherever they appear, including inside a
 *      WHERE subquery, before parsing. The Python leans on this same scan.
 *   2. real parse (JSQLParser); exactly ONE statement or fail-closed.
 *   3. statement class must be SELECT (incl. WITH/set-ops), EXPLAIN SELECT, or
 *      DESCRIBE.
 *   4. every referenced table must be `data` or a query-local CTE name.
 *
 * Divergence note (for the migration record): the Python's dead BANNED_KEYWORDS
 * set is intentionally NOT ported — validate() never uses it; statement-class +
 * single-statement enforcement already covers those.
 */
object SqlPolicy {

    private const val ALLOWED_TABLE = "data"

    // Mirrors Python BANNED_SUBSTRINGS + read_csv (both DuckDB table-function
    // file readers). Matched case-insensitively on the whole (explain-stripped)
    // text — a legitimate analytical query over the fixed `data` view does not
    // contain these; matching Python's acceptance of that tradeoff IS the
    // contract.
    private val BANNED_SUBSTRINGS = listOf(
        "s3://", "http://", "https://", "file://", "/etc/",
        "read_parquet", "read_csv", "read_json", "read_ndjson",
        "read_text", "read_blob", "parquet_scan", "glob(",
    )

    /**
     * HEL-112 parity: `allowedTables` mirrors Python `validate(allowed_tables=)`
     * — the workspace's registered aliases (default = the legacy single dataset
     * `data`). Query-local CTE names are always additionally allowed.
     */
    fun validate(sql: String, allowedTables: Set<String> = setOf(ALLOWED_TABLE)): String {
        if (sql.isBlank()) throw UnsafeSql("empty query")
        val inner = stripExplain(sql)
        val lowered = inner.lowercase()
        for (bad in BANNED_SUBSTRINGS) {
            if (bad in lowered) throw UnsafeSql("forbidden reference: '$bad'")
        }

        val statements = try {
            CCJSqlParserUtil.parseStatements(inner)
        } catch (e: Exception) {
            throw UnsafeSql("could not parse SQL: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        if (statements.size != 1) throw UnsafeSql("exactly one statement is allowed")

        val allowed = allowedTables.map { it.lowercase() }.toSet()
        when (val st = statements.first()) {
            is DescribeStatement -> return sql.trim().trimEnd(';')
            is Select -> checkTables(st, allowed)
            is ExplainStatement -> (st.statement as? Select)?.let { checkTables(it, allowed) }
                ?: throw UnsafeSql("EXPLAIN only over a SELECT is allowed")
            else -> throw UnsafeSql(
                "only SELECT/WITH queries are allowed (got ${st.javaClass.simpleName})")
        }
        return sql.trim().trimEnd(';')
    }

    private fun stripExplain(sql: String): String {
        val s = sql.trim().trimEnd(';').trim()
        return if (s.lowercase().startsWith("explain")) s.substring("explain".length).trim() else s
    }

    private fun checkTables(select: Select, allowed: Set<String>) {
        val cteNames = buildSet {
            select.withItemsList?.forEach { it.alias?.name?.lowercase()?.let(::add) }
        }
        val tables = try {
            TablesNamesFinder.findTables(select.toString())
        } catch (e: Exception) {
            throw UnsafeSql("table analysis failed: ${e.message}")
        }
        for (t in tables) {
            val bare = t.substringAfterLast('.').trim('"').lowercase()
            if (bare !in allowed && bare !in cteNames) {
                val names = allowed.sorted().joinToString(", ")
                throw UnsafeSql("only these tables may be queried: $names (found '$t')")
            }
        }
    }

    fun hasLimit(sql: String): Boolean = try {
        val inner = stripExplain(sql)
        ((CCJSqlParserUtil.parse(inner) as? Select)?.selectBody() as? PlainSelect)?.limit != null
    } catch (e: Exception) {
        "limit" in sql.lowercase()
    }

    private fun Select.selectBody(): Any = if (this is PlainSelect) this else this
}
