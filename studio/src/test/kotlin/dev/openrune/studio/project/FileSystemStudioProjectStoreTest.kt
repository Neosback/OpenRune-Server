package dev.openrune.studio.project

import dev.openrune.studio.format.ProjectCacheIdentityV1
import dev.openrune.studio.format.StudioFormatV1Codec
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class FileSystemStudioProjectStoreTest {
    @TempDir
    lateinit var tempDir: Path

    private val base =
        ProjectCacheIdentityV1(
            game = "oldschool",
            revision = 240,
            name = "OSRS revision 240",
            fingerprint = "fixture:osrs-240",
        )

    @Test
    fun `create trims the name and creates an empty edit batch`() {
        val store =
            FileSystemStudioProjectStore(
                root = tempDir.resolve("projects"),
                now = { 1_700_000_000_000L },
                idFactory = { "project-1" },
            )

        val project =
            store.createProject(
                CreateStudioProjectInput(
                    name = "  My project  ",
                    base = base,
                ),
            )

        assertEquals("project-1", project.id)
        assertEquals("My project", project.name)
        assertEquals(1_700_000_000_000L, project.createdAt)
        assertEquals(project.createdAt, project.updatedAt)
        assertEquals("project-1:edits", project.edits.id)
        assertEquals(project.createdAt, project.edits.createdAt)
        assertTrue(project.edits.transactions.isEmpty())
        assertEquals(project, store.loadProject(project.id))
    }

    @Test
    fun `create and import reject duplicate ids`() {
        val store = store()

        store.createProject(
            CreateStudioProjectInput(
                id = "same-id",
                name = "First",
                base = base,
            ),
        )

        val createError =
            assertThrows<StudioProjectStoreException> {
                store.createProject(
                    CreateStudioProjectInput(
                        id = "same-id",
                        name = "Second",
                        base = base,
                    ),
                )
            }
        assertEquals(StudioProjectStoreErrorCode.CONFLICT, createError.code)

        val serialized = store.exportProject("same-id")
        val importError =
            assertThrows<StudioProjectStoreException> {
                store.importProject(serialized)
            }
        assertEquals(StudioProjectStoreErrorCode.CONFLICT, importError.code)
    }

    @Test
    fun `save load export import and delete preserve the portable project contract`() {
        val firstRoot = tempDir.resolve("first")
        val first = FileSystemStudioProjectStore(firstRoot, now = { 100L }, idFactory = { "project-a" })
        val created =
            first.createProject(
                CreateStudioProjectInput(
                    name = "Project A",
                    base = base,
                ),
            )

        val saved = created.copy(name = "Renamed", updatedAt = 200L)
        first.saveProject(saved)

        assertEquals(saved, first.loadProject(saved.id))

        val exported = first.exportProject(saved.id)
        assertEquals(saved, StudioFormatV1Codec.decodeProject(exported))

        val second = FileSystemStudioProjectStore(tempDir.resolve("second"))
        assertEquals(saved, second.importProject(exported))
        assertEquals(saved, second.loadProject(saved.id))

        second.deleteProject(saved.id)
        assertNull(second.loadProject(saved.id))
        second.deleteProject(saved.id)
    }

    @Test
    fun `export reports a missing project`() {
        val error =
            assertThrows<StudioProjectStoreException> {
                store().exportProject("missing")
            }

        assertEquals(StudioProjectStoreErrorCode.NOT_FOUND, error.code)
    }

    @Test
    fun `invalid create input and invalid import report invalid project`() {
        val store = store()

        val emptyName =
            assertThrows<StudioProjectStoreException> {
                store.createProject(
                    CreateStudioProjectInput(
                        name = "   ",
                        base = base,
                    ),
                )
            }
        assertEquals(StudioProjectStoreErrorCode.INVALID_PROJECT, emptyName.code)

        val invalidImport =
            assertThrows<StudioProjectStoreException> {
                store.importProject("{")
            }
        assertEquals(StudioProjectStoreErrorCode.INVALID_PROJECT, invalidImport.code)
    }

    @Test
    fun `list sorts by updated time then name then id and skips corrupt files`() {
        var timestamp = 100L
        val root = tempDir.resolve("projects")
        val store =
            FileSystemStudioProjectStore(
                root = root,
                now = { timestamp },
                idFactory = { error("explicit ids expected") },
            )

        store.createProject(CreateStudioProjectInput(id = "b", name = "Zulu", base = base))
        timestamp = 300L
        store.createProject(CreateStudioProjectInput(id = "c", name = "Beta", base = base))
        store.createProject(CreateStudioProjectInput(id = "a", name = "Alpha", base = base))

        Files.writeString(root.resolve("corrupt.openrune-project.json"), "{")

        assertEquals(
            listOf("a", "c", "b"),
            store.listProjects().map(StudioProjectSummary::id),
        )
    }

    @Test
    fun `project ids never become filesystem paths`() {
        val root = tempDir.resolve("projects")
        val store =
            FileSystemStudioProjectStore(
                root = root,
                now = { 100L },
            )
        val unsafeId = "../../outside/evil"

        val project =
            store.createProject(
                CreateStudioProjectInput(
                    id = unsafeId,
                    name = "Traversal test",
                    base = base,
                ),
            )

        assertEquals(unsafeId, project.id)
        assertEquals(project, store.loadProject(unsafeId))
        assertFalse(Files.exists(tempDir.resolve("outside")))

        val storedFiles =
            Files.list(root).use { paths ->
                paths.toList()
            }
        assertEquals(1, storedFiles.size)
        assertTrue(
            storedFiles.single().fileName.toString()
                .matches(Regex("[0-9a-f]{64}\\.openrune-project\\.json")),
        )
    }

    @Test
    fun `stored identity mismatch is rejected`() {
        val root = tempDir.resolve("projects")
        val store =
            FileSystemStudioProjectStore(
                root = root,
                now = { 100L },
                idFactory = { "expected-id" },
            )
        val project =
            store.createProject(
                CreateStudioProjectInput(
                    name = "Identity",
                    base = base,
                ),
            )

        val storedFile =
            Files.list(root).use { paths ->
                paths.findFirst().orElseThrow()
            }
        val mismatched = project.copy(id = "different-id")
        Files.writeString(storedFile, StudioFormatV1Codec.encodeProject(mismatched))

        val error =
            assertThrows<StudioProjectStoreException> {
                store.loadProject("expected-id")
            }
        assertEquals(StudioProjectStoreErrorCode.INVALID_PROJECT, error.code)
    }

    @Test
    fun `save leaves no temporary files behind`() {
        val root = tempDir.resolve("projects")
        val store =
            FileSystemStudioProjectStore(
                root = root,
                now = { 100L },
                idFactory = { "project-1" },
            )
        val project =
            store.createProject(
                CreateStudioProjectInput(
                    name = "Atomic",
                    base = base,
                ),
            )

        store.saveProject(project.copy(name = "Atomic saved", updatedAt = 101L))

        val files = Files.list(root).use { it.toList() }
        assertEquals(1, files.size)
        assertNotNull(store.loadProject(project.id))
        assertTrue(files.none { it.fileName.toString().endsWith(".tmp") })
    }

    private fun store(): FileSystemStudioProjectStore =
        FileSystemStudioProjectStore(
            root = tempDir.resolve("projects"),
            now = { 100L },
            idFactory = { "generated-id" },
        )
}
