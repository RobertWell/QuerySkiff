package com.queryskiff.workspace

import com.queryskiff.datasets.Datasets
import com.queryskiff.workspace.Workspace.WorkspaceError
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** HEL-95: parity with `queryskiff.workspace` — alias rules, collisions,
 *  forged ids, hint kinds. Messages mirror the Python module (HTTP contract). */
class WorkspaceParityTest {
    private val buckets = listOf("contractbkt")
    private fun id(key: String) = Datasets.encodeId("contractbkt", key)

    @Test
    fun `valid two-entry workspace resolves`() {
        val es = Workspace.resolveEntries(listOf(
            mapOf("dataset_id" to id("a.parquet"), "alias" to "rows"),
            mapOf("dataset_id" to id("b.parquet"), "alias" to "prices")), buckets)
        assertEquals(listOf("rows", "prices"), es.map { it.alias })
    }

    @Test
    fun `alias rules - invalid shapes rejected`() {
        for (bad in listOf("1bad", "SELECT", "has space", "x".repeat(31), "")) {
            assertThrows<WorkspaceError> {
                Workspace.resolveEntries(listOf(
                    mapOf("dataset_id" to id("a.parquet"), "alias" to bad)), buckets)
            }
        }
    }

    @Test
    fun `reserved and duplicate aliases and duplicate datasets rejected`() {
        assertThrows<WorkspaceError> {  // reserved
            Workspace.resolveEntries(listOf(
                mapOf("dataset_id" to id("a.parquet"), "alias" to "select")), buckets)
        }
        val dup = assertThrows<WorkspaceError> {
            Workspace.resolveEntries(listOf(
                mapOf("dataset_id" to id("a.parquet"), "alias" to "t"),
                mapOf("dataset_id" to id("b.parquet"), "alias" to "t")), buckets)
        }
        assertTrue("duplicate alias" in dup.message!!)
        assertThrows<WorkspaceError> {  // same dataset twice
            Workspace.resolveEntries(listOf(
                mapOf("dataset_id" to id("a.parquet"), "alias" to "t1"),
                mapOf("dataset_id" to id("a.parquet"), "alias" to "t2")), buckets)
        }
    }

    @Test
    fun `forged id fails with browser-safe message`() {
        val e = assertThrows<WorkspaceError> {
            Workspace.resolveEntries(listOf(
                mapOf("dataset_id" to Datasets.encodeId("evil", "x.parquet"),
                      "alias" to "x")), buckets)
        }
        assertTrue("not in an allowed bucket" in e.message!!)
        assertFalse("s3://" in e.message!!)
    }

    @Test
    fun `max datasets enforced`() {
        val many = (0..8).map { mapOf("dataset_id" to id("f$it.parquet"), "alias" to "a$it") }
        val e = assertThrows<WorkspaceError> { Workspace.resolveEntries(many, buckets) }
        assertTrue("at most 8" in e.message!!)
    }

    @Test
    fun `hint kinds - numeric vs text incompatibility carries a cast note`() {
        val hints = Workspace.joinHints(mapOf(
            "a" to listOf(mapOf("column_name" to "x", "column_type" to "BIGINT"),
                          mapOf("column_name" to "only_a", "column_type" to "VARCHAR")),
            "b" to listOf(mapOf("column_name" to "x", "column_type" to "VARCHAR"))))
        assertEquals(1, hints.size)                    // single-owner columns excluded
        val x = hints.single()
        assertFalse(x.compatible)
        assertTrue("cast before joining" in x.note!!)
    }

    @Test
    fun `numeric width differences are compatible`() {
        val hints = Workspace.joinHints(mapOf(
            "a" to listOf(mapOf("column_name" to "n", "column_type" to "INTEGER")),
            "b" to listOf(mapOf("column_name" to "n", "column_type" to "DOUBLE"))))
        assertTrue(hints.single().compatible)
    }
}
