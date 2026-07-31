package com.queryskiff.workspace

import com.queryskiff.datasets.Datasets

/**
 * HEL-95 phase 4: JVM port of `queryskiff.workspace` (HEL-112) — the
 * multi-dataset query workspace. Per-request (dataset, alias) entries; the
 * server resolves opaque ids (forged ids fail exactly like the single-dataset
 * path), validates every alias, and exposes each dataset to SQL only under its
 * alias. Behavior mirrors the Python module 1:1 (messages included) so the
 * HTTP workspace contract passes unchanged.
 */
object Workspace {
    private val ALIAS_RE = Regex("^[a-z][a-z0-9_]{0,29}$")
    private val RESERVED = setOf(
        "select", "from", "where", "join", "on", "with", "as", "group", "order",
        "by", "limit", "union", "all", "distinct", "having", "case", "when",
        "then", "else", "end", "and", "or", "not", "null", "true", "false",
        "table", "values", "minio", "pg", "oracle", "system",
    )
    const val MAX_DATASETS = 8

    class WorkspaceError(message: String) : Exception(message)

    data class Entry(val dataset: Datasets.Dataset, val alias: String)

    /** [{dataset_id, alias}] -> validated entries; browser-safe errors. */
    fun resolveEntries(
        payload: List<Map<String, Any?>>,
        allowedBuckets: Collection<String>,
    ): List<Entry> {
        if (payload.isEmpty()) throw WorkspaceError("workspace needs at least one dataset")
        if (payload.size > MAX_DATASETS)
            throw WorkspaceError("at most $MAX_DATASETS datasets per workspace")
        val entries = mutableListOf<Entry>()
        val seenAliases = mutableSetOf<String>()
        val seenIds = mutableSetOf<String>()
        for (item in payload) {
            val alias = (item["alias"]?.toString() ?: "").trim().lowercase()
            if (!ALIAS_RE.matches(alias))
                throw WorkspaceError(
                    "invalid alias '$alias' (lowercase letters/digits/underscore, " +
                    "must start with a letter, max 30 chars)")
            if (alias in RESERVED) throw WorkspaceError("alias '$alias' is reserved")
            if (alias in seenAliases) throw WorkspaceError("duplicate alias '$alias'")
            val ds = try {
                Datasets.resolveId(item["dataset_id"]?.toString() ?: "", allowedBuckets)
            } catch (e: Datasets.DatasetError) {
                throw WorkspaceError("dataset for alias '$alias': ${e.message}")
            }
            if (ds.datasetId in seenIds)
                throw WorkspaceError("dataset appears twice (alias '$alias')")
            seenAliases += alias
            seenIds += ds.datasetId
            entries += Entry(ds, alias)
        }
        return entries
    }

    private val NUMERIC = setOf(
        "tinyint", "smallint", "integer", "bigint", "float", "double",
        "real", "decimal", "hugeint", "ubigint", "uinteger",
    )

    private fun kind(t: String): String {
        val k = t.lowercase().substringBefore("(").trim()
        return when {
            k in NUMERIC -> "numeric"
            "timestamp" in k || k == "date" -> "temporal"
            "char" in k || k in setOf("varchar", "text", "string") -> "text"
            else -> k
        }
    }

    data class HintOwner(val alias: String, val type: String)
    data class Hint(val column: String, val aliases: List<HintOwner>,
                    val compatible: Boolean, val note: String?)

    /** Column-compatibility hints across workspace schemas (hints only). */
    fun joinHints(schemas: Map<String, List<Map<String, Any?>>>): List<Hint> {
        val cols = linkedMapOf<String, MutableList<HintOwner>>()
        for ((alias, schema) in schemas) {
            for (col in schema) {
                val name = (col["column_name"] ?: col["name"] ?: "").toString().lowercase()
                val ctype = (col["column_type"] ?: col["type"] ?: "").toString()
                if (name.isNotEmpty())
                    cols.getOrPut(name) { mutableListOf() }.add(HintOwner(alias, ctype))
            }
        }
        return cols.entries.sortedBy { it.key }.mapNotNull { (name, owners) ->
            if (owners.size < 2) return@mapNotNull null
            val kinds = owners.map { kind(it.type) }.toSet()
            Hint(
                column = name, aliases = owners, compatible = kinds.size == 1,
                note = if (kinds.size == 1) null
                       else "type kinds differ (${kinds.sorted().joinToString(", ")}) — cast before joining",
            )
        }
    }

    /** Editable starter SQL: joins the first two aliases on the best hint. */
    fun starterJoinSql(entries: List<Entry>, hints: List<Hint>): String {
        if (entries.size < 2) return "SELECT * FROM ${entries[0].alias} LIMIT 100"
        val a = entries[0].alias
        val b = entries[1].alias
        val best = hints.firstOrNull { h ->
            h.compatible && h.aliases.map { it.alias }.toSet().containsAll(setOf(a, b))
        }
        return if (best != null) {
            val c = best.column
            "SELECT *\nFROM $a\nJOIN $b ON $a.$c = $b.$c\nLIMIT 100"
        } else {
            "SELECT *\nFROM $a\nJOIN $b ON /* TODO: join condition */ 1=1\nLIMIT 100"
        }
    }
}
