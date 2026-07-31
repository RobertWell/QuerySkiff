package com.queryskiff.resource

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

/**
 * HEL-95: root health + SPA fallback, mirroring `queryskiff.app`:
 *   - {base}/health -> {"ok": true}
 *   - any non-API path -> built index.html when present (bundled from the
 *     frontend build into META-INF/resources), else an honest
 *     404 {"error": "frontend not built"} — never a 5xx.
 * Static assets under {base}/assets are served by Quarkus' static resource
 * handling from META-INF/resources (the frontend build lands there in the
 * image, matching the Python STATIC_DIR mount).
 */
@ApplicationScoped
@Path("/")
class SpaResource {

    private fun index(): ByteArray? =
        javaClass.getResourceAsStream("/META-INF/resources/queryskiff/index.html")
            ?.readBytes()

    @GET
    @Path("/queryskiff/health")
    @Produces(MediaType.APPLICATION_JSON)
    fun health(): Any = mapOf("ok" to true)

    @GET
    @Path("/queryskiff{path: (/.*)?}")
    fun spa(@PathParam("path") path: String?): Response {
        val p = path ?: ""
        // API + health handled by their own resources; assets by static handling
        if (p.startsWith("/api") || p == "/health" || p.startsWith("/assets")) {
            return Response.status(404).entity(mapOf("detail" to "not found"))
                .type(MediaType.APPLICATION_JSON).build()
        }
        val html = index()
            ?: return Response.status(404)
                .entity(mapOf("error" to "frontend not built"))
                .type(MediaType.APPLICATION_JSON).build()
        return Response.ok(html, MediaType.TEXT_HTML).build()
    }
}
