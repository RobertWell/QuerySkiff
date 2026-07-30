package com.queryskiff.datasets

import com.queryskiff.datasets.Datasets.DatasetError
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * HEL-95: parity of the opaque-id + redaction contract with the Python
 * `queryskiff.datasets`. The golden ids below were minted by the Python
 * backend (null-byte `\x00` separator) — they MUST resolve here byte-for-byte,
 * proving ids are portable across the two backends during the migration.
 */
class DatasetsParityTest {
    private val allowed = listOf("model-results", "stable-stock")

    // minted by Python encode_id (see HEL-95 comment)
    private val FILE_ID = "bW9kZWwtcmVzdWx0cwBkYXRhc2V0cy9zdG9ja19oaXN0XzkwZC5wYXJxdWV0"
    private val FOLDER_ID = "bW9kZWwtcmVzdWx0cwBkYXRhc2V0cy8"

    @Test
    fun `resolves a python-minted file id byte-for-byte`() {
        val ds = Datasets.resolveId(FILE_ID, allowed)
        assertEquals("model-results", ds.bucket)
        assertEquals("datasets/stock_hist_90d.parquet", ds.key)
        assertFalse(ds.isFolder)
        assertEquals("stock_hist_90d", ds.label)
    }

    @Test
    fun `resolves a python-minted folder id`() {
        val ds = Datasets.resolveId(FOLDER_ID, allowed)
        assertTrue(ds.isFolder)
        assertEquals("datasets/", ds.key)
        assertEquals("s3://model-results/datasets/*.parquet", Datasets.s3Uri(ds))
    }

    @Test
    fun `encode then resolve round-trips`() {
        val id = Datasets.encodeId("model-results", "a/b/c.parquet")
        assertFalse(id.contains("=")) // no padding, matches Python
        val ds = Datasets.resolveId(id, allowed)
        assertEquals("a/b/c.parquet", ds.key)
        // and the id equals what Python would mint for the same input
        assertEquals(FILE_ID.take(20), Datasets.encodeId(
            "model-results", "datasets/stock_hist_90d.parquet").take(20))
    }

    @Test
    fun `forged bucket rejected`() {
        val forged = Datasets.encodeId("evil-bucket", "x.parquet")
        val e = assertThrows<DatasetError> { Datasets.resolveId(forged, allowed) }
        assertEquals("dataset not in an allowed bucket", e.message)
    }

    @Test
    fun `path traversal rejected`() {
        val forged = Datasets.encodeId("model-results", "../../etc/passwd.parquet")
        assertThrows<DatasetError> { Datasets.resolveId(forged, allowed) }
    }

    @Test
    fun `non-parquet object rejected`() {
        val forged = Datasets.encodeId("model-results", "secret.txt")
        val e = assertThrows<DatasetError> { Datasets.resolveId(forged, allowed) }
        assertEquals("not a parquet dataset", e.message)
    }

    @Test
    fun `garbage id rejected`() {
        assertThrows<DatasetError> { Datasets.resolveId("!!!not base64!!!", allowed) }
    }

    @Test
    fun `label strips parquet extension for files only`() {
        assertEquals("rows", Datasets.displayLabel("a/b/rows.parquet", false))
        assertEquals("b", Datasets.displayLabel("a/b/", true))
        assertEquals("dataset", Datasets.displayLabel("", false))
    }

    @Test
    fun `redact strips s3 uris and bucket names`() {
        val msg = "IO error reading s3://model-results/datasets/x.parquet on model-results"
        val out = Datasets.redact(msg, allowed)
        assertFalse(out.contains("s3://"))
        assertFalse(out.contains("model-results"))
        assertTrue(out.contains("<dataset>"))
    }
}
