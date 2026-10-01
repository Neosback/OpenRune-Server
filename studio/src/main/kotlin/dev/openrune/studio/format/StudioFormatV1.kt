package dev.openrune.studio.format

public const val EDIT_FORMAT_V1_NAME: String = "openrune.edit-batch"
public const val EDIT_FORMAT_V1_VERSION: Int = 1

public const val PROJECT_FORMAT_V1_NAME: String = "openrune.project"
public const val PROJECT_FORMAT_V1_VERSION: Int = 1

public data class StudioFormatIssue(
    val path: String,
    val message: String,
)

public class StudioFormatException(
    message: String,
    public val issues: List<StudioFormatIssue> = emptyList(),
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

public data class EditMapRefV1(
    val x: Int,
    val y: Int,
)

public data class EditTileSnapshotV1(
    val h: Long? = null,
    val hl: List<Long>? = null,
    val u: Long? = null,
    val o: Long? = null,
    val s: Long? = null,
    val r: Long? = null,
    val f: Long? = null,
)

public data class EditLocV1(
    val id: Long,
    val flags: Long,
    val worldX: Long,
    val worldY: Long,
)

public sealed interface EditMutationV1 {
    public val mapX: Int
    public val mapY: Int
    public val level: Int
}

public data class EditTileMutationV1(
    override val mapX: Int,
    override val mapY: Int,
    override val level: Int,
    val localX: Int,
    val localY: Int,
    val before: EditTileSnapshotV1,
    val after: EditTileSnapshotV1,
) : EditMutationV1

public data class EditObjectMutationV1(
    override val mapX: Int,
    override val mapY: Int,
    override val level: Int,
    val before: List<EditLocV1>,
    val after: List<EditLocV1>,
) : EditMutationV1

public enum class EditTransactionSourceV1(public val wireName: String) {
    UNDERLAY("underlay"),
    OVERLAY("overlay"),
    HEIGHT("height"),
    SMOOTH("smooth"),
    OBJECT_SELECTOR("object-selector"),
    OBJECT_DELETE("object-delete"),
    REGION_STAMP("region-stamp"),
    TILE_FLAGS("tile-flags"),
    SANDBOX("sandbox"),
    BULK("bulk"),
    ;

    public companion object {
        private val byWireName: Map<String, EditTransactionSourceV1> =
            entries.associateBy(EditTransactionSourceV1::wireName)

        public fun fromWireName(value: String): EditTransactionSourceV1? = byWireName[value]
    }
}

public data class EditTransactionV1(
    val id: String,
    val label: String,
    val source: EditTransactionSourceV1,
    val timestamp: Long,
    val mutations: List<EditMutationV1>,
    val affectedMaps: List<EditMapRefV1>,
    val tileCount: Long,
)

public data class EditBatchV1(
    val id: String,
    val createdAt: Long,
    val transactions: List<EditTransactionV1>,
    val format: String = EDIT_FORMAT_V1_NAME,
    val version: Int = EDIT_FORMAT_V1_VERSION,
)

public data class ProjectCacheIdentityV1(
    val game: String,
    val revision: Long,
    val profileId: String? = null,
    val name: String? = null,
    val fingerprint: String? = null,
    val kind: String = "cache",
)

public data class ProjectV1(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val base: ProjectCacheIdentityV1,
    val edits: EditBatchV1,
    val format: String = PROJECT_FORMAT_V1_NAME,
    val version: Int = PROJECT_FORMAT_V1_VERSION,
)
