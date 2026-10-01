package dev.openrune.studio.format

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class StudioFormatV1ParityTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `edit golden fixture decodes and round trips semantically`() {
        val json = fixture("edit-format-v1.golden.json")
        val batch = StudioFormatV1Codec.decodeEditBatch(json)

        assertEquals("golden-map-edit-v1", batch.id)
        assertEquals(1_700_000_000_100L, batch.createdAt)
        assertEquals(1, batch.transactions.size)

        val transaction = batch.transactions.single()
        assertEquals(EditTransactionSourceV1.REGION_STAMP, transaction.source)
        assertEquals(listOf(EditMapRefV1(50, 51)), transaction.affectedMaps)
        assertEquals(1L, transaction.tileCount)

        val tile = assertInstanceOf(EditTileMutationV1::class.java, transaction.mutations[0])
        assertEquals(2, tile.localX)
        assertEquals(3, tile.localY)
        assertEquals(100L, tile.before.h)
        assertEquals(120L, tile.after.h)

        val objects = assertInstanceOf(EditObjectMutationV1::class.java, transaction.mutations[1])
        assertEquals(EditLocV1(100, 2, 3202, 3267), objects.before.single())
        assertEquals(EditLocV1(200, 66, 3204, 3269), objects.after.single())

        assertEquals(
            mapper.readTree(json),
            mapper.readTree(StudioFormatV1Codec.encodeEditBatch(batch)),
        )
    }

    @Test
    fun `edit validation rejects unknown fields and inconsistent derived metadata`() {
        val root = mapper.readTree(fixture("edit-format-v1.golden.json")) as ObjectNode
        root.put("extra", true)
        val transaction = root["transactions"][0] as ObjectNode
        transaction.put("tileCount", 9)
        transaction.set<ArrayNode>(
            "affectedMaps",
            mapper.createArrayNode().add(mapper.createObjectNode().put("x", 1).put("y", 2)),
        )

        val issues = StudioFormatV1Codec.validateEditBatch(root)
        val paths = issues.map(StudioFormatIssue::path)

        assertTrue("$.extra" in paths)
        assertTrue("$.transactions[0].tileCount" in paths)
        assertTrue("$.transactions[0].affectedMaps" in paths)
    }

    @Test
    fun `edit decoder rejects unsupported version malformed json and unsafe integer`() {
        val unsupported = mapper.readTree(fixture("edit-format-v1.golden.json")) as ObjectNode
        unsupported.put("version", 2)
        assertThrows<StudioFormatException> {
            StudioFormatV1Codec.decodeEditBatch(unsupported.toString())
        }

        assertThrows<StudioFormatException> {
            StudioFormatV1Codec.decodeEditBatch("{")
        }

        val unsafe = mapper.readTree(fixture("edit-format-v1.golden.json")) as ObjectNode
        unsafe.put("createdAt", 9_007_199_254_740_992L)
        assertTrue(
            StudioFormatV1Codec
                .validateEditBatch(unsafe)
                .any { it.path == "$.createdAt" },
        )
    }

    @Test
    fun `project golden fixture decodes and round trips semantically`() {
        val json = fixture("project-v1.golden.json")
        val project = StudioFormatV1Codec.decodeProject(json)

        assertEquals("golden-project-v1", project.id)
        assertEquals("Golden project", project.name)
        assertEquals(
            ProjectCacheIdentityV1(
                game = "oldschool",
                revision = 225,
                name = "OSRS revision 225",
                fingerprint = "fixture:osrs-225",
            ),
            project.base,
        )

        assertEquals(
            mapper.readTree(json),
            mapper.readTree(StudioFormatV1Codec.encodeProject(project)),
        )
    }

    @Test
    fun `project validation rejects unknown fields unsupported version and timestamp reversal`() {
        val root = mapper.readTree(fixture("project-v1.golden.json")) as ObjectNode
        root.put("extra", true)
        root.put("version", 2)
        root.put("updatedAt", root["createdAt"].longValue() - 1)

        val issues = StudioFormatV1Codec.validateProject(root)
        val paths = issues.map(StudioFormatIssue::path)

        assertTrue("$.extra" in paths)
        assertTrue("$.version" in paths)
        assertTrue("$.updatedAt" in paths)
    }

    @Test
    fun `project validation reports nested edit failures with project relative paths`() {
        val root = mapper.readTree(fixture("project-v1.golden.json")) as ObjectNode
        (root["edits"] as ObjectNode).put("version", 99)

        val issues = StudioFormatV1Codec.validateProject(root)

        assertTrue(
            StudioFormatIssue("$.edits.version", "Expected version 1.") in issues,
        )
    }

    @Test
    fun `project decoder rejects malformed json`() {
        assertThrows<StudioFormatException> {
            StudioFormatV1Codec.decodeProject("{")
        }
    }

    private fun fixture(name: String): String =
        requireNotNull(
            javaClass.getResource("/openrune-studio-contract/$name"),
        ).readText()
}
