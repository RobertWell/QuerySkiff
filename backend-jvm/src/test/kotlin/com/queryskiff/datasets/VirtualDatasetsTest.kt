package com.queryskiff.datasets

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** HEL-121: registry semantics with in-memory storage — save/reopen, opaque
 *  ids, query-time revalidation (fail closed), expiry, thresholds, managed
 *  marking. Object keys never appear in the API projection beyond ids. */
class VirtualDatasetsTest {

    private class MemStorage : VirtualDatasets.Storage {
        val map = LinkedHashMap<String, String>()
        override fun put(id: String, json: String) { map[id] = json }
        override fun get(id: String) = map[id]
        override fun delete(id: String) = map.remove(id) != null
        override fun list() = map.values.toList()
    }

    private val allowed = listOf("model-results")
    private fun id(key: String) = Datasets.encodeId("model-results", key)

    @Test
    fun `save and reopen with stable opaque id`() {
        val v = VirtualDatasets(MemStorage(), allowed)
        val (rec, warnings) = v.save("prices pair",
            listOf(id("a/x.parquet"), id("a/y.parquet")),
            VirtualDatasets.SchemaPolicy.UNION_BY_NAME)
        assertTrue(rec.id.matches(Regex("^v_[0-9a-f]{18}$")))
        assertTrue(warnings.isEmpty())
        val (rec2, members, _) = v.open(rec.id)
        assertEquals(rec.id, rec2.id)
        assertEquals(2, members.size)
        assertEquals("x.parquet", members[0].key.substringAfterLast("/"))
        assertEquals(VirtualDatasets.SchemaPolicy.UNION_BY_NAME, rec2.schemaPolicy)
    }

    @Test
    fun `members are revalidated at open time and fail closed`() {
        val store = MemStorage()
        val v = VirtualDatasets(store, allowed)
        val (rec, _) = v.save("s", listOf(id("k.parquet")))
        // permissions change: same registry, narrower allowlist
        val revoked = VirtualDatasets(store, listOf("other-bucket"))
        val ex = assertThrows(VirtualDatasets.VirtualDatasetError::class.java) {
            revoked.open(rec.id)
        }
        assertTrue(ex.message!!.contains("no longer authorized"))
        assertFalse(ex.message!!.contains("model-results"))   // no leakage
    }

    @Test
    fun `expiry threshold and hard cap`() {
        val v = VirtualDatasets(MemStorage(), allowed, warnFileCount = 2, maxFileCount = 3)
        val (rec, warns) = v.save("big", (1..3).map { id("f$it.parquet") })
        assertTrue(warns.single().contains("promotion"))
        assertThrows(VirtualDatasets.VirtualDatasetError::class.java) {
            v.save("too big", (1..4).map { id("f$it.parquet") })
        }
        val (expired, _) = v.save("old", listOf(id("f.parquet")),
            expiresAt = "2020-01-01T00:00:00Z")
        assertThrows(VirtualDatasets.VirtualDatasetError::class.java) { v.open(expired.id) }
        assertEquals(3, v.open(rec.id).second.size)
    }

    @Test
    fun `managed marking records promotion state without doing promotion`() {
        val v = VirtualDatasets(MemStorage(), allowed)
        val (rec, _) = v.save("s", listOf(id("k.parquet")))
        val managed = v.markManaged(rec.id, "minio", "ds", "t_abc")
        assertEquals(VirtualDatasets.Mode.MANAGED, managed.mode)
        assertEquals("t_abc", managed.promotedTable)
        assertTrue(v.toApi(managed)["promoted"] as Boolean)
    }

    @Test
    fun `bad ids and bad saves are rejected by name-free errors`() {
        val v = VirtualDatasets(MemStorage(), allowed)
        assertThrows(VirtualDatasets.VirtualDatasetError::class.java) { v.getRecord("../etc") }
        assertThrows(VirtualDatasets.VirtualDatasetError::class.java) { v.getRecord("v_zzz") }
        assertThrows(VirtualDatasets.VirtualDatasetError::class.java) {
            v.save("x", emptyList())
        }
        assertThrows(VirtualDatasets.VirtualDatasetError::class.java) {
            v.save("", listOf(id("k.parquet")))
        }
    }
}

/** Regression (security review 2026-08-01): virtual-entry aliases must pass
 *  the SAME gate as plain workspace aliases — a quote-bearing alias would
 *  otherwise reach view DDL. */
class VirtualAliasGateTest {
    @org.junit.jupiter.api.Test
    fun `evil aliases are rejected by the shared gate`() {
        for (evil in listOf("x\" AS SELECT 1 --", "A", "1bad", "with space",
                            "x".repeat(31), "x\\\"y")) {
            org.junit.jupiter.api.Assertions.assertThrows(
                com.queryskiff.workspace.Workspace.WorkspaceError::class.java) {
                com.queryskiff.workspace.Workspace.validateAlias(evil)
            }
        }
        org.junit.jupiter.api.Assertions.assertEquals(
            "ok_alias", com.queryskiff.workspace.Workspace.validateAlias("ok_alias"))
    }
}
