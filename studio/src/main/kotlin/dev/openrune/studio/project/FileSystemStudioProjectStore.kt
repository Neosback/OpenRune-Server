package dev.openrune.studio.project

import dev.openrune.studio.format.EditBatchV1
import dev.openrune.studio.format.ProjectV1
import dev.openrune.studio.format.StudioFormatException
import dev.openrune.studio.format.StudioFormatV1Codec
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

public class FileSystemStudioProjectStore(
    private val root: Path,
    private val now: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : StudioProjectStore {
    private val lock = ReentrantReadWriteLock()

    override fun listProjects(): List<StudioProjectSummary> =
        lock.read {
            if (!Files.exists(root)) {
                return@read emptyList()
            }
            try {
                Files.newDirectoryStream(root, "*$PROJECT_FILE_SUFFIX").use { entries ->
                    entries
                        .mapNotNull(::readSummaryIfValid)
                        .sortedWith(
                            compareByDescending<StudioProjectSummary> { it.updatedAt }
                                .thenBy { it.name }
                                .thenBy { it.id },
                        )
                }
            } catch (exception: IOException) {
                throw storageFailure("Failed to list Studio projects.", exception)
            }
        }

    override fun createProject(input: CreateStudioProjectInput): ProjectV1 =
        lock.write {
            val name = input.name.trim()
            if (name.isEmpty()) {
                throw StudioProjectStoreException(
                    StudioProjectStoreErrorCode.INVALID_PROJECT,
                    "Project name must not be empty.",
                )
            }

            val generatedId = input.id?.trim().orEmpty().ifEmpty { idFactory().trim() }
            val timestamp = now()
            val project =
                ProjectV1(
                    id = generatedId,
                    name = name,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                    base = input.base,
                    edits =
                        input.edits
                            ?: EditBatchV1(
                                id = "$generatedId:edits",
                                createdAt = timestamp,
                                transactions = emptyList(),
                            ),
                )
            val canonical = canonicalize(project)
            writeNew(canonical)
            canonical
        }

    override fun loadProject(id: String): ProjectV1? =
        lock.read {
            readStoredProject(id)
        }

    override fun saveProject(project: ProjectV1) {
        lock.write {
            val canonical = canonicalize(project)
            writeProject(canonical, replaceExisting = true)
        }
    }

    override fun deleteProject(id: String) {
        lock.write {
            try {
                Files.deleteIfExists(projectPath(id))
            } catch (exception: IOException) {
                throw storageFailure("Failed to delete Studio project.", exception)
            }
        }
    }

    override fun exportProject(id: String): String =
        lock.read {
            val project =
                readStoredProject(id)
                    ?: throw StudioProjectStoreException(
                        StudioProjectStoreErrorCode.NOT_FOUND,
                        "Project \"$id\" was not found.",
                    )
            StudioFormatV1Codec.encodeProject(project)
        }

    override fun importProject(serialized: String): ProjectV1 =
        lock.write {
            val project =
                try {
                    StudioFormatV1Codec.decodeProject(serialized)
                } catch (exception: StudioFormatException) {
                    throw StudioProjectStoreException(
                        StudioProjectStoreErrorCode.INVALID_PROJECT,
                        exception.message ?: "Invalid Studio project.",
                        exception,
                    )
                }
            writeNew(project)
            project
        }

    private fun readSummaryIfValid(path: Path): StudioProjectSummary? =
        try {
            val project = StudioFormatV1Codec.decodeProject(Files.readString(path))
            if (path.fileName.toString() != projectFileName(project.id)) {
                null
            } else {
                project.toSummary()
            }
        } catch (_: StudioFormatException) {
            null
        } catch (_: IOException) {
            null
        }

    private fun readStoredProject(id: String): ProjectV1? {
        val path = projectPath(id)
        if (!Files.exists(path)) {
            return null
        }
        try {
            val project = StudioFormatV1Codec.decodeProject(Files.readString(path))
            if (project.id != id) {
                throw StudioProjectStoreException(
                    StudioProjectStoreErrorCode.INVALID_PROJECT,
                    "Stored project \"$id\" does not match its storage identity.",
                )
            }
            return project
        } catch (exception: StudioProjectStoreException) {
            throw exception
        } catch (exception: StudioFormatException) {
            throw StudioProjectStoreException(
                StudioProjectStoreErrorCode.INVALID_PROJECT,
                "Stored project \"$id\" is corrupt or uses an unsupported version.",
                exception,
            )
        } catch (exception: IOException) {
            throw storageFailure("Failed to read Studio project.", exception)
        }
    }

    private fun canonicalize(project: ProjectV1): ProjectV1 =
        try {
            StudioFormatV1Codec.decodeProject(
                StudioFormatV1Codec.encodeProject(project, pretty = false),
            )
        } catch (exception: StudioFormatException) {
            throw StudioProjectStoreException(
                StudioProjectStoreErrorCode.INVALID_PROJECT,
                "Cannot persist an invalid Studio project.",
                exception,
            )
        }

    private fun writeNew(project: ProjectV1) {
        val target = projectPath(project.id)
        if (Files.exists(target)) {
            throw conflict(project.id)
        }
        try {
            writeAtomically(
                target = target,
                payload = StudioFormatV1Codec.encodeProject(project),
                replaceExisting = false,
            )
        } catch (exception: FileAlreadyExistsException) {
            throw conflict(project.id, exception)
        } catch (exception: IOException) {
            throw storageFailure("Failed to create Studio project.", exception)
        }
    }

    private fun writeProject(
        project: ProjectV1,
        replaceExisting: Boolean,
    ) {
        try {
            writeAtomically(
                target = projectPath(project.id),
                payload = StudioFormatV1Codec.encodeProject(project),
                replaceExisting = replaceExisting,
            )
        } catch (exception: IOException) {
            throw storageFailure("Failed to save Studio project.", exception)
        }
    }

    private fun writeAtomically(
        target: Path,
        payload: String,
        replaceExisting: Boolean,
    ) {
        Files.createDirectories(root)
        val temp = Files.createTempFile(root, ".openrune-studio-project-", ".tmp")
        try {
            Files.writeString(
                temp,
                payload,
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            FileChannel.open(temp, StandardOpenOption.WRITE).use { channel ->
                channel.force(true)
            }

            val atomicOptions =
                if (replaceExisting) {
                    arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } else {
                    arrayOf(StandardCopyOption.ATOMIC_MOVE)
                }
            try {
                Files.move(temp, target, *atomicOptions)
            } catch (_: AtomicMoveNotSupportedException) {
                val fallbackOptions =
                    if (replaceExisting) {
                        arrayOf(StandardCopyOption.REPLACE_EXISTING)
                    } else {
                        emptyArray()
                    }
                Files.move(temp, target, *fallbackOptions)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun projectPath(id: String): Path = root.resolve(projectFileName(id))

    private fun projectFileName(id: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(StandardCharsets.UTF_8))
        val hash =
            digest.joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        return hash + PROJECT_FILE_SUFFIX
    }

    private fun conflict(
        id: String,
        cause: Throwable? = null,
    ): StudioProjectStoreException =
        StudioProjectStoreException(
            StudioProjectStoreErrorCode.CONFLICT,
            "A project with id \"$id\" already exists.",
            cause,
        )

    private fun storageFailure(
        message: String,
        cause: Throwable,
    ): StudioProjectStoreException =
        StudioProjectStoreException(
            StudioProjectStoreErrorCode.STORAGE_FAILED,
            message,
            cause,
        )

    private companion object {
        private const val PROJECT_FILE_SUFFIX = ".openrune-project.json"
    }
}
