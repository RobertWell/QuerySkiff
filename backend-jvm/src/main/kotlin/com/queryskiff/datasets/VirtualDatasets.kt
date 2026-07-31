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
) {

    /** Pluggable persistence: MinIO in production, in-memory in tests. */
    interface Storage {
        fun put(id: String, json: String)
        fun get(id: String): String?
        fun delete(id: String): Boolean
        fun list(): List<String>          // record JSONs
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
        if (datasetIds.isEmpty()) throw VirtualDatasetError("a virtual dataset needs at least one member")
        if (datasetIds.size > maxFileCount)
            throw VirtualDatasetError("selection exceeds the $maxFileCount-file limit — promote to a managed table instead")
        if (displayName.isBlank() || displayName.length > 120)
            throw VirtualDatasetError("display name must be 1..120 characters")
        val members = datasetIds.map { Datasets.resolveId(it, allowedBuckets) }
        val warnings = thresholdWarnings(members.size)
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
            if (Instant.parse(it).isBefore(Instant.now()))
                throw VirtualDatasetError("virtual dataset has expired")
        }
        val members = rec.datasetIds.map {
            try { Datasets.resolveId(it, allowedBuckets) }
            catch (e: Datasets.DatasetError) {
                throw VirtualDatasetError("a member of this virtual dataset is no longer authorized")
            }
        }
        return Triple(rec, members, thresholdWarnings(members.size))
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
        return updated
    }

    private fun thresholdWarnings(count: Int): List<String> =
        if (count > warnFileCount)
            listOf("selection spans $count files (> $warnFileCount): consider compaction " +
                   "or explicit promotion to a managed table for stable performance")
        else emptyList()

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
