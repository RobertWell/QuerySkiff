package com.queryskiff.resource

import com.queryskiff.config.QsConfig
import com.queryskiff.datasets.Datasets
import com.queryskiff.datasets.MinioListing
import com.queryskiff.datasets.MinioRegistryStorage
import com.queryskiff.datasets.VirtualDatasets
import com.queryskiff.engine.DuckDbEngine
import com.queryskiff.engine.QueryEngine
import com.queryskiff.engine.TrinoEngine
import com.queryskiff.registrar.Registrar
import com.queryskiff.sql.UnsafeSql
import com.queryskiff.workspace.Workspace
import io.minio.MinioClient
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * HEL-95: the QuerySkiff API under {base}/api — routes, status codes and
 * response shapes mirror `queryskiff.app` exactly (the dual-target contract
 * suite is the arbiter), including its pinned quirks:
 *   - runtime SQL errors: 200 submit -> status "error" -> 400 results
 *   - DELETE of unknown/settled query: 200 {"cancelled": false}, not 404
 *   - 400 detail strings ("unsafe SQL: ...", "dataset_id or datasets required")
 */
@ApplicationScoped
@Path("/queryskiff/api")
@Produces(MediaType.APPLICATION_JSON)
class ApiResource(private val config: QsConfig) {

    // The DuckDB engine always exists: it is the default query path AND the
    // registrar's footer sniffer under Trino (schema without a data scan).
    private val duckEngine: DuckDbEngine by lazy {
        DuckDbEngine(DuckDbEngine.EngineConfig(
            minioEndpoint = config.minioEndpoint,
            minioAccessKey = config.minioAccessKey,
            minioSecretKey = config.minioSecretKey,
            minioSecure = config.minioSecure,
            defaultLimit = config.defaultLimit,
            maxResultRows = config.maxResultRows,
            maxRunningQueries = config.maxRunningQueries,
            timeoutSeconds = config.timeoutSeconds,
            memoryLimit = config.memoryLimit,
            tempDir = config.tempDir,
            allowedBuckets = config.allowedBuckets,
        ))
    }

    // HEL-112/113 engine dispatch, mirroring `app.py`: QUERYSKIFF_ENGINE=trino
    // selects the shared engine + auto-registration; anything else = DuckDB.
    private val engine: QueryEngine by lazy {
        if (config.engine == "trino") {
            val trinoConfig = TrinoEngine.Config(
                host = config.trinoHost, port = config.trinoPort,
                catalog = config.trinoCatalog, schema = config.trinoSchema,
                defaultLimit = config.defaultLimit, maxResultRows = config.maxResultRows,
                maxRunningQueries = config.maxRunningQueries,
                timeoutSeconds = config.timeoutSeconds,
                allowedBuckets = config.allowedBuckets,
            )
            val registrar = Registrar(
                Registrar.Config(config.trinoCatalog, config.trinoSchema,
                                 config.trinoManagedBucket, config.trinoManagedPrefix),
                sniffer = { ds -> duckEngine.schemaOf(ds) },
                connFactory = { TrinoEngine.openConnection(trinoConfig) },
                minioFactory = {
                    MinioClient.builder()
                        .endpoint("http${if (config.minioSecure) "s" else ""}://${config.minioEndpoint}")
                        .credentials(config.minioAccessKey, config.minioSecretKey)
                        .build()
                },
            )
            TrinoEngine(trinoConfig, registrar)
        } else duckEngine
    }

    // HEL-121: the virtual-dataset registry (records in MinIO; opaque v_ ids)
    private val virtual: VirtualDatasets by lazy {
        VirtualDatasets(
            MinioRegistryStorage({
                MinioClient.builder()
                    .endpoint("http${if (config.minioSecure) "s" else ""}://${config.minioEndpoint}")
                    .credentials(config.minioAccessKey, config.minioSecretKey)
                    .build()
            }, config.registryBucket, config.registryPrefix),
            config.allowedBuckets,
            warnFileCount = config.virtualWarnFiles,
            maxFileCount = config.virtualMaxFiles)
    }

    private val listing: MinioListing by lazy {
        MinioListing(config.minioEndpoint, config.minioAccessKey,
                     config.minioSecretKey, config.minioSecure, config.allowedBuckets)
    }

    private fun err(status: Int, detail: String): Response =
        Response.status(status).entity(mapOf("detail" to detail)).build()

    private fun resolveOr404(id: String): Datasets.Dataset =
        try { Datasets.resolveId(id, config.allowedBuckets) }
        catch (e: Datasets.DatasetError) { throw NotFound(e.message ?: "unknown dataset") }

    private class NotFound(val detail: String) : RuntimeException(detail)

    @GET
    @Path("/datasets")
    fun listDatasets(): Any = try {
        mapOf("datasets" to listing.listDatasets())
    } catch (e: Exception) {
        err(502, "could not list datasets: ${Datasets.redact(e.message, config.allowedBuckets)}")
    }

    @GET
    @Path("/datasets/{id}/schema")
    fun schema(@PathParam("id") id: String): Any = try {
        mapOf("schema" to engine.schemaOf(resolveOr404(id)))
    } catch (e: NotFound) {
        err(404, e.detail)
    } catch (e: Exception) {
        err(502, "could not read schema: ${Datasets.redact(e.message, config.allowedBuckets)}")
    }

    @GET
    @Path("/datasets/{id}/metadata")
    fun metadata(@PathParam("id") id: String): Any = try {
        listing.objectMetadata(resolveOr404(id))
    } catch (e: NotFound) {
        err(404, e.detail)
    } catch (e: Exception) {
        err(502, "could not read metadata: ${Datasets.redact(e.message, config.allowedBuckets)}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun entriesFromBody(body: Map<String, Any?>): List<Pair<Datasets.Dataset, String>> {
        val ws = body["datasets"] as? List<Map<String, Any?>>
        if (ws != null) {
            // HEL-121: an entry may reference a SAVED virtual dataset instead
            // of a single object id — it expands to all (re-validated) members
            // under ONE alias, forming a single multi-file logical source.
            val plain = mutableListOf<Map<String, Any?>>()
            val expanded = mutableListOf<Pair<Datasets.Dataset, String>>()
            for (e in ws) {
                val vid = e["virtual_id"]?.toString()
                if (vid == null) { plain += e; continue }
                val alias = e["alias"]?.toString()
                    ?: throw Workspace.WorkspaceError("virtual dataset entries need an alias")
                val (rec, members, _) = try { virtual.open(vid) }
                    catch (ex: VirtualDatasets.VirtualDatasetError) {
                        throw Workspace.WorkspaceError(ex.message ?: "bad virtual dataset")
                    }
                val union = rec.schemaPolicy == VirtualDatasets.SchemaPolicy.UNION_BY_NAME
                expanded += members.map { it.copy(unionByName = union) to alias }
            }
            val resolved = if (plain.isNotEmpty())
                Workspace.resolveEntries(plain, config.allowedBuckets).map { it.dataset to it.alias }
            else emptyList()
            val all = resolved + expanded
            if (all.isEmpty()) throw Workspace.WorkspaceError("datasets must not be empty")
            // alias uniqueness ACROSS entries (HEL-112 rule): virtual members
            // deliberately share their own alias, but no two ENTRIES may claim
            // the same one.
            val entryAliases = resolved.map { it.second } + expanded.map { it.second }.distinct()
            val clash = entryAliases.groupBy { it }.filterValues { g -> g.size > 1 }.keys
            if (clash.isNotEmpty())
                throw Workspace.WorkspaceError("duplicate alias: ${clash.first()}")
            return all
        }
        val id = body["dataset_id"]?.toString()
            ?: throw Workspace.WorkspaceError("dataset_id or datasets required")
        return listOf(resolveOr404(id) to "data")
    }

    // ── HEL-121 virtual-dataset registry endpoints ─────────────────────────

    @GET
    @Path("/virtual-datasets")
    fun listVirtual(): Any = try {
        mapOf("virtual_datasets" to virtual.list().map { virtual.toApi(it) })
    } catch (e: Exception) {
        err(502, "could not list virtual datasets: ${Datasets.redact(e.message, config.allowedBuckets)}")
    }

    @POST
    @Path("/virtual-datasets")
    fun saveVirtual(body: Map<String, Any?>?): Any {
        val b = body ?: emptyMap()
        val name = b["display_name"]?.toString() ?: return err(400, "display_name required")
        @Suppress("UNCHECKED_CAST")
        val ids = (b["dataset_ids"] as? List<*>)?.map { it.toString() }
            ?: return err(400, "dataset_ids required")
        val policy = when (b["schema_policy"]?.toString()?.uppercase()) {
            null, "STRICT" -> VirtualDatasets.SchemaPolicy.STRICT
            "UNION_BY_NAME" -> VirtualDatasets.SchemaPolicy.UNION_BY_NAME
            else -> return err(400, "schema_policy must be STRICT or UNION_BY_NAME")
        }
        return try {
            val (rec, warnings) = virtual.save(name, ids, policy,
                owner = b["owner"]?.toString(), expiresAt = b["expires_at"]?.toString())
            virtual.toApi(rec, warnings)
        } catch (e: VirtualDatasets.VirtualDatasetError) {
            err(400, e.message ?: "bad virtual dataset")
        } catch (e: Datasets.DatasetError) {
            err(400, e.message ?: "bad member dataset")
        }
    }

    @GET
    @Path("/virtual-datasets/{id}")
    fun getVirtual(@PathParam("id") id: String): Any = try {
        val (rec, _, warnings) = virtual.open(id)
        virtual.toApi(rec, warnings)
    } catch (e: VirtualDatasets.VirtualDatasetError) {
        err(404, e.message ?: "unknown virtual dataset")
    }

    @DELETE
    @Path("/virtual-datasets/{id}")
    fun deleteVirtual(@PathParam("id") id: String): Any =
        mapOf("deleted" to virtual.delete(id))

    @POST
    @Path("/queries")
    fun submit(body: Map<String, Any?>?): Any {
        val b = body ?: emptyMap()
        val sql = b["sql"]?.toString() ?: ""
        val entries = try {
            entriesFromBody(b)
        } catch (e: Workspace.WorkspaceError) {
            return err(400, e.message ?: "bad workspace")
        } catch (e: NotFound) {
            return err(404, e.detail)
        }
        val q = try {
            engine.createQuery(entries, sql)
        } catch (e: UnsafeSql) {
            return err(400, "unsafe SQL: ${e.message}")
        }
        return mapOf("query_id" to q.id, "status" to q.status)
    }

    @POST
    @Path("/workspace/hints")
    fun hints(body: Map<String, Any?>?): Any {
        @Suppress("UNCHECKED_CAST")
        val ws = (body?.get("datasets") as? List<Map<String, Any?>>) ?: emptyList()
        val entries = try {
            Workspace.resolveEntries(ws, config.allowedBuckets)
        } catch (e: Workspace.WorkspaceError) {
            return err(400, e.message ?: "bad workspace")
        }
        val schemas = mutableMapOf<String, List<Map<String, Any?>>>()
        for (e in entries) {
            try {
                schemas[e.alias] = engine.schemaOf(e.dataset)
            } catch (ex: Exception) {
                return err(502, "could not read schema for alias '${e.alias}': " +
                                Datasets.redact(ex.message, config.allowedBuckets))
            }
        }
        val hints = Workspace.joinHints(schemas)
        return mapOf(
            "hints" to hints.map { h -> mapOf(
                "column" to h.column,
                "aliases" to h.aliases.map { mapOf("alias" to it.alias, "type" to it.type) },
                "compatible" to h.compatible, "note" to h.note) },
            "starter_sql" to Workspace.starterJoinSql(entries, hints),
            "schemas" to schemas,
        )
    }

    @GET
    @Path("/queries/{id}")
    fun status(@PathParam("id") id: String): Any {
        val q = engine.getQuery(id) ?: return err(404, "unknown query")
        return mapOf("query_id" to q.id, "status" to q.status, "error" to q.error,
                     "row_count" to q.rowCount, "truncated" to q.truncated)
    }

    @GET
    @Path("/queries/{id}/results")
    fun results(@PathParam("id") id: String): Any {
        val q = engine.getQuery(id) ?: return err(404, "unknown query")
        if (q.status == "error") return err(400, q.error ?: "query failed")
        return mapOf("query_id" to q.id, "status" to q.status, "columns" to q.columns,
                     "rows" to q.rows, "row_count" to q.rowCount, "truncated" to q.truncated)
    }

    @DELETE
    @Path("/queries/{id}")
    fun cancel(@PathParam("id") id: String): Any =
        mapOf("cancelled" to engine.cancelQuery(id))   // quirk: never 404

    @GET
    @Path("/health")
    fun health(): Any = mapOf("ok" to true)
}
