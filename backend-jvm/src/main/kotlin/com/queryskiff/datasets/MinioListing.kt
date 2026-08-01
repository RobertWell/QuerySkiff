package com.queryskiff.datasets

import io.minio.ListObjectsArgs
import io.minio.MinioClient
import io.minio.StatObjectArgs

/**
 * HEL-95: JVM port of the MinIO-touching half of `queryskiff.datasets` —
 * dataset discovery + object metadata via the MinIO Java SDK. Browser-safe
 * shapes only (opaque id + logical label; never bucket/key/etag), mirroring
 * the HEL-90 contract the Python listing enforces.
 */
class MinioListing(
    private val endpoint: String,
    private val accessKey: String,
    private val secretKey: String,
    private val secure: Boolean,
    private val allowedBuckets: List<String>,
) {
    private fun client(): MinioClient = MinioClient.builder()
        .endpoint("http${if (secure) "s" else ""}://$endpoint")
        .credentials(accessKey, secretKey)
        .build()

    /** Every parquet object across the allowed buckets, plus one folder entry
     *  per prefix holding multiple parts. Buckets that error are skipped, not
     *  fatal (mirrors the Python listing's per-bucket try). */
    fun listDatasets(): List<Map<String, Any?>> {
        val c = client()
        val out = mutableListOf<Map<String, Any?>>()
        val folders = mutableMapOf<Pair<String, String>, Int>()
        for (bucket in allowedBuckets) {
            val objects = try {
                c.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket).recursive(true).build()).map { it.get() }
            } catch (e: Exception) {
                continue
            }
            for (obj in objects) {
                val key = obj.objectName()
                if (!key.lowercase().endsWith(".parquet")) continue
                out += mapOf(
                    "dataset_id" to Datasets.encodeId(bucket, key),
                    "name" to Datasets.displayLabel(key, false),
                    "kind" to "file",
                    "size" to obj.size(),
                    "modified" to (obj.lastModified()?.toString()),
                )
                if ("/" in key) {
                    val prefix = key.substringBeforeLast("/") + "/"
                    folders.merge(bucket to prefix, 1, Int::plus)
                }
            }
        }
        for ((bp, n) in folders) {
            if (n > 1) {
                val (bucket, prefix) = bp
                out += mapOf(
                    "dataset_id" to Datasets.encodeId(bucket, prefix),
                    "name" to "${Datasets.displayLabel(prefix, true)} ($n parts)",
                    "kind" to "folder", "parts" to n,
                    "size" to null, "modified" to null,
                )
            }
        }
        return out.sortedBy { it["name"].toString() }
    }

    /** Total on-disk bytes for a dataset: a file's object size, or the sum of
     *  every parquet part under a folder prefix. Used by the HEL-121 byte
     *  budget. Object keys never leave this layer. */
    fun totalBytes(ds: Datasets.Dataset): Long {
        val c = client()
        if (!ds.isFolder) {
            return c.statObject(
                StatObjectArgs.builder().bucket(ds.bucket).`object`(ds.key).build()).size()
        }
        return c.listObjects(ListObjectsArgs.builder()
            .bucket(ds.bucket).prefix(ds.key).recursive(true).build())
            .map { it.get() }
            .filter { it.objectName().lowercase().endsWith(".parquet") }
            .sumOf { it.size() }
    }

    /** Browser-safe metadata: logical label + size/modified/content-type only. */
    fun objectMetadata(ds: Datasets.Dataset): Map<String, Any?> {
        if (ds.isFolder) return mapOf("kind" to "folder", "name" to ds.label)
        val st = client().statObject(
            StatObjectArgs.builder().bucket(ds.bucket).`object`(ds.key).build())
        return mapOf(
            "kind" to "file", "name" to ds.label,
            "size" to st.size(),
            "modified" to (st.lastModified()?.toString()),
            "content_type" to st.contentType(),
        )
    }
}
