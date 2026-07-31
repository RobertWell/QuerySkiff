package com.queryskiff.datasets

import io.minio.GetObjectArgs
import io.minio.ListObjectsArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import java.io.ByteArrayInputStream

/** HEL-121: virtual-dataset records persisted as small JSON objects in MinIO
 *  (`<prefix><id>.json`) — stateless pods, no extra database. */
class MinioRegistryStorage(
    private val client: () -> MinioClient,
    private val bucket: String,
    private val prefix: String,
) : VirtualDatasets.Storage {

    private fun key(id: String) = "$prefix$id.json"

    override fun put(id: String, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        client().putObject(PutObjectArgs.builder()
            .bucket(bucket).`object`(key(id))
            .stream(ByteArrayInputStream(bytes), bytes.size.toLong(), -1)
            .contentType("application/json").build())
    }

    override fun get(id: String): String? = try {
        client().getObject(GetObjectArgs.builder()
            .bucket(bucket).`object`(key(id)).build()).readBytes().toString(Charsets.UTF_8)
    } catch (e: Exception) { null }

    override fun delete(id: String): Boolean = try {
        client().removeObject(RemoveObjectArgs.builder()
            .bucket(bucket).`object`(key(id)).build()); true
    } catch (e: Exception) { false }

    override fun list(): List<String> = try {
        client().listObjects(ListObjectsArgs.builder()
            .bucket(bucket).prefix(prefix).recursive(true).build())
            .mapNotNull { r ->
                runCatching {
                    client().getObject(GetObjectArgs.builder()
                        .bucket(bucket).`object`(r.get().objectName()).build())
                        .readBytes().toString(Charsets.UTF_8)
                }.getOrNull()
            }
    } catch (e: Exception) { emptyList() }
}
