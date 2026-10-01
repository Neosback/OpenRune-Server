package dev.openrune.studio.project

import dev.openrune.studio.format.EditBatchV1
import dev.openrune.studio.format.ProjectCacheIdentityV1
import dev.openrune.studio.format.ProjectV1

public data class StudioProjectSummary(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val base: ProjectCacheIdentityV1,
)

public data class CreateStudioProjectInput(
    val name: String,
    val base: ProjectCacheIdentityV1,
    val id: String? = null,
    val edits: EditBatchV1? = null,
)

public enum class StudioProjectStoreErrorCode {
    INVALID_PROJECT,
    CONFLICT,
    NOT_FOUND,
    STORAGE_FAILED,
}

public class StudioProjectStoreException(
    public val code: StudioProjectStoreErrorCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

public interface StudioProjectStore {
    public fun listProjects(): List<StudioProjectSummary>

    public fun createProject(input: CreateStudioProjectInput): ProjectV1

    public fun loadProject(id: String): ProjectV1?

    public fun saveProject(project: ProjectV1)

    public fun deleteProject(id: String)

    public fun exportProject(id: String): String

    public fun importProject(serialized: String): ProjectV1
}

public fun ProjectV1.toSummary(): StudioProjectSummary =
    StudioProjectSummary(
        id = id,
        name = name,
        createdAt = createdAt,
        updatedAt = updatedAt,
        base = base,
    )
