package com.queryskiff.datasets

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.security.SecureRandom
import java.time.Instant

/**
 * HEL-121: saved VIRTUAL datasets — reusable logical file selections that are
 * REGISTRY OBJECTS, not Trino tables. The file-first model:
 *
 *   EPHEMERAL  — a workspace selection in one request (exists since HEL-112)
 *   VIRTUAL    — a saved selection (this registry): opaque id -> authorized
 *                object ids + schema policy + engine preference
 *   MANAGED    — explicit promotion into a durable table (state modeled here;
 *                the promotion mechanics are a separate task by design)
 *
 * Security: records store DATASET IDS (opaque, allowlist-validated), never
 * raw paths. Every open RE-RESOLVES each member through Datasets.resolveId —
 * revoked buckets or illegal keys fail closed at query time, not just at
 * save time. Nothing in the browser payload contains object keys.
 */
class VirtualDatasets(
    private val storage: Storage,
    private val allowedBuckets: List<String>,
    /** Above this many member files we WARN and recommend promotion. */
    private val warnFileCount: Int = 64,
    /** Hard cap on members per virtual dataset. */
    private val maxFileCount: Int = 512,
    /** Above this many total input bytes we WARN (compaction/promotion hint). */
    private val warnBytes: Long = Long.MAX_VALUE,
    /** Hard cap on total input bytes for a saved selection. */
    private val maxBytes: Long = Long.MAX_VALUE,
    /** Resolve the on-disk byte size of a member. Null (default) leaves the
     *  byte budget INERT — file-count limits still apply. In production this is
     *  MinioListing::totalBytes; tests stub it to exercise the budget. */
    private val sizeOf: ((Datasets.Dataset) -> Long)? = null,
    /** Observability seam — no-op by default, Micrometer-backed in production.
     *  Records save/open admission + rejection, limit pressure, and promotion
     *  events so the virtual path is measurable (HEL-121). */
    private val metrics: Metrics = Metrics.NOOP,
) {

    /** Pluggable persistence: MinIO in production, in-memory in tests. */
    interface Storage {
        fun put(id: String, json: String)
        fun get(id: String): String?
        fun delete(id: String): Boolean
        fun list(): List<String>          // record JSONs
    }

    /**
     * Retained telemetry for the virtual-dataset lifecycle. Bounded-cardinality
     * by design — outcomes/reasons are a small fixed set, never the (unbounded)
     * virtual id — so scraping stays cheap. `reason` ∈ {bytes, files, revoked,
     * expired, empty, too_many_files, bad_name, unknown}; `kind` ∈ {files, bytes}.
     */
    interface Metrics {
        fun saved(memberCount: Int, totalBytes: Long?) {}
        fun saveRejected(reason: String) {}
        fun opened(memberCount: Int) {}
        fun openRejected(reason: String) {}
        fun limitPressure(kind: String) {}
        fun promoted() {}
        companion object { val NOOP = object : Metrics {} }
    }

    enum class Mode { VIRTUAL, MANAGED }
    enum class SchemaPolicy { STRICT, UNION_BY_NAME }

    data class Record(
        val id: String,
        val displayName: String,
        val mode: Mode = Mode.VIRTUAL,
        val owner: String? = null,
        val datasetIds: List<String>,
        val schemaPolicy: SchemaPolicy = SchemaPolicy.STRICT,
        val schemaFingerprint: String? = null,
        val enginePreference: String = "duckdb",
        val createdAt: String = Instant.now().toString(),
        val updatedAt: String = Instant.now().toString(),
        val expiresAt: String? = null,
        val promotedCatalog: String? = null,
        val promotedSchema: String? = null,
        val promotedTable: String? = null,
    )

    class VirtualDatasetError(message: String) : RuntimeException(message)

    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val rng = SecureRandom()

    private fun newId(): String =
        "v_" + ByteArray(9).also(rng::nextBytes).joinToString("") { "%02x".format(it) }

    /**
     * Save a selection. Every member id is validated NOW (and again at every
     * open). Returns the stored record incl. any threshold warnings.
     */
    fun save(displayName: String, datasetIds: List<String>,
             schemaPolicy: SchemaPolicy = SchemaPolicy.STRICT,
             owner: String? = null, expiresAt: String? = null,
             schemaFingerprint: String? = null): Pair<Record, List<String>> {
        if (datasetIds.isEmpty()) { metrics.saveRejected("empty"); throw VirtualDatasetError("a virtual dataset needs at least one member") }
        if (datasetIds.size > maxFileCount) {
            metrics.saveRejected("too_many_files")
            throw VirtualDatasetError("selection exceeds the $maxFileCount-file limit — promote to a managed table instead")
        }
        if (displayName.isBlank() || displayName.length > 120) {
            metrics.saveRejected("bad_name")
            throw VirtualDatasetError("display name must be 1..120 characters")
        }
        val members = datasetIds.map { Datasets.resolveId(it, allowedBuckets) }
        val totalBytes = totalBytesOrNull(members)
        if (totalBytes != null && totalBytes > maxBytes) {
            metrics.saveRejected("bytes")
            throw VirtualDatasetError(
                "selection totals ${humanBytes(totalBytes)} (> ${humanBytes(maxBytes)} limit) — " +
                "compact the parquet or promote to a managed table instead")
        }
        val warnings = thresholdWarnings(members.size, totalBytes)
        metrics.saved(members.size, totalBytes)
        val rec = Record(id = newId(), displayName = displayName.trim(),
                         datasetIds = datasetIds, schemaPolicy = schemaPolicy,
                         owner = owner, expiresAt = expiresAt,
                         schemaFingerprint = schemaFingerprint)
        storage.put(rec.id, mapper.writeValueAsString(rec))
        return rec to warnings
    }

    /**
     * Open for querying: re-resolve EVERY member against the CURRENT
     * allowlist (fail-closed revalidation), honor expiry, and return the live
     * Dataset list plus threshold warnings.
     */
    fun open(id: String): Triple<Record, List<Datasets.Dataset>, List<String>> {
        val rec = getRecord(id)
        rec.expiresAt?.let {
            if (Instant.parse(it).isBefore(Instant.now())) {
                metrics.openRejected("expired")
                throw VirtualDatasetError("virtual dataset has expired")
            }
        }
        val members = rec.datasetIds.map {
            try { Datasets.resolveId(it, allowedBuckets) }
            catch (e: Datasets.DatasetError) {
                metrics.openRejected("revoked")
                throw VirtualDatasetError("a member of this virtual dataset is no longer authorized")
            }
        }
        metrics.opened(members.size)
        return Triple(rec, members, thresholdWarnings(members.size, totalBytesOrNull(members)))
    }

    fun getRecord(id: String): Record {
        if (!id.matches(Regex("^v_[0-9a-f]{18}$")))
            throw VirtualDatasetError("unknown virtual dataset")
        val json = storage.get(id) ?: throw VirtualDatasetError("unknown virtual dataset")
        return mapper.readValue(json)
    }

    fun list(): List<Record> = storage.list().map { mapper.readValue<Record>(it) }
        .sortedByDescending { it.updatedAt }

    fun delete(id: String): Boolean = storage.delete(id)

    /** Mark eligibility/state for explicit promotion — the mechanics of the
     *  Iceberg/Trino commit live in a separate task (HEL-121 boundary). */
    fun markManaged(id: String, catalog: String, schema: String, table: String): Record {
        val rec = getRecord(id)
        val updated = rec.copy(mode = Mode.MANAGED, promotedCatalog = catalog,
                               promotedSchema = schema, promotedTable = table,
                               updatedAt = Instant.now().toString())
        storage.put(updated.id, mapper.writeValueAsString(updated))
        metrics.promoted()
        return updated
    }

    /** Sum member sizes; null if no size provider is wired (budget inert). */
    private fun totalBytesOrNull(members: List<Datasets.Dataset>): Long? =
        sizeOf?.let { f -> members.sumOf { f(it) } }

    private fun thresholdWarnings(count: Int, totalBytes: Long?): List<String> {
        val out = mutableListOf<String>()
        if (count > warnFileCount) {
            metrics.limitPressure("files")
            out += "selection spans $count files (> $warnFileCount): consider compaction " +
                   "or explicit promotion to a managed table for stable performance"
        }
        if (totalBytes != null && totalBytes > warnBytes) {
            metrics.limitPressure("bytes")
            out += "selection totals ${humanBytes(totalBytes)} (> ${humanBytes(warnBytes)}): " +
                   "consider compaction or explicit promotion to a managed table"
        }
        return out
    }

    private fun humanBytes(n: Long): String {
        if (n < 1L shl 10) return "${n}B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = n.toDouble() / (1L shl 10)
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return "%.1f%s".format(v, units[i])
    }

    /** Browser-safe projection (no object keys — dataset ids are opaque). */
    fun toApi(rec: Record, warnings: List<String> = emptyList()): Map<String, Any?> = mapOf(
        "id" to rec.id, "display_name" to rec.displayName,
        "mode" to rec.mode.name, "owner" to rec.owner,
        "member_count" to rec.datasetIds.size,
        "dataset_ids" to rec.datasetIds,
        "schema_policy" to rec.schemaPolicy.name,
        "engine_preference" to rec.enginePreference,
        "created_at" to rec.createdAt, "updated_at" to rec.updatedAt,
        "expires_at" to rec.expiresAt,
        "promoted" to (rec.mode == Mode.MANAGED),
        "warnings" to warnings,
    )
}
