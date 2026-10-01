package dev.openrune.studio.http

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.openrune.studio.format.ProjectCacheIdentityV1
import dev.openrune.studio.format.StudioFormatException
import dev.openrune.studio.format.StudioFormatV1Codec
import dev.openrune.studio.project.CreateStudioProjectInput
import dev.openrune.studio.project.StudioProjectStoreErrorCode
import dev.openrune.studio.project.StudioProjectStoreException
import dev.openrune.studio.project.StudioProjectSummary
import java.math.BigInteger

public object StudioProjectApiCodec {
    private val mapper = jacksonObjectMapper()
    private val maxSafeInteger = BigInteger.valueOf(9_007_199_254_740_991L)
    private val createKeys = setOf("id", "name", "base", "edits")
    private val baseKeys = setOf("kind", "game", "revision", "profileId", "name", "fingerprint")

    public fun decodeCreateProject(input: String): CreateStudioProjectInput {
        val root =
            try {
                mapper.readTree(input)
            } catch (exception: JsonProcessingException) {
                throw invalid("Invalid create-project JSON.", exception)
            }
        if (root == null || !root.isObject) {
            throw invalid("Expected create-project object.")
        }
        val objectNode = root as ObjectNode
        rejectUnknownKeys(objectNode, createKeys, "create project")

        val id =
            if (objectNode.has("id")) {
                requireText(objectNode["id"], "id").trim().ifEmpty { null }
            } else {
                null
            }
        val name = requireText(objectNode["name"], "name", nonEmpty = true)
        val base = decodeBase(objectNode["base"])
        val edits =
            if (objectNode.has("edits")) {
                try {
                    StudioFormatV1Codec.decodeEditBatch(objectNode["edits"].toString())
                } catch (exception: StudioFormatException) {
                    throw invalid("Invalid Edit Format v1 payload.", exception)
                }
            } else {
                null
            }

        return CreateStudioProjectInput(
            id = id,
            name = name,
            base = base,
            edits = edits,
        )
    }

    public fun encodeSummaries(summaries: List<StudioProjectSummary>): String {
        val array = mapper.createArrayNode()
        summaries.forEach { summary ->
            array.add(
                mapper.createObjectNode().apply {
                    put("id", summary.id)
                    put("name", summary.name)
                    put("createdAt", summary.createdAt)
                    put("updatedAt", summary.updatedAt)
                    set<ObjectNode>("base", encodeBase(summary.base))
                },
            )
        }
        return mapper.writeValueAsString(array)
    }

    public fun encodeError(
        code: String,
        message: String,
    ): String =
        mapper.writeValueAsString(
            mapper.createObjectNode().apply {
                put("error", code)
                put("message", message)
            },
        )

    public fun encodeSession(
        endpoint: String,
        token: String,
    ): String =
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
            mapper.createObjectNode().apply {
                put("version", 1)
                put("endpoint", endpoint)
                put("token", token)
            },
        )

    private fun decodeBase(node: JsonNode?): ProjectCacheIdentityV1 {
        if (node == null || !node.isObject) {
            throw invalid("Expected base cache identity object.")
        }
        val base = node as ObjectNode
        rejectUnknownKeys(base, baseKeys, "base cache identity")
        if (requireText(base["kind"], "base.kind") != "cache") {
            throw invalid("Expected base.kind to be \"cache\".")
        }
        val game = requireText(base["game"], "base.game", nonEmpty = true)
        val revision = requireSafeInteger(base["revision"], "base.revision", min = 0)
        return ProjectCacheIdentityV1(
            game = game,
            revision = revision,
            profileId = optionalNonEmptyText(base, "profileId"),
            name = optionalNonEmptyText(base, "name"),
            fingerprint = optionalNonEmptyText(base, "fingerprint"),
        )
    }

    private fun encodeBase(base: ProjectCacheIdentityV1): ObjectNode =
        mapper.createObjectNode().apply {
            put("kind", base.kind)
            put("game", base.game)
            put("revision", base.revision)
            base.profileId?.let { put("profileId", it) }
            base.name?.let { put("name", it) }
            base.fingerprint?.let { put("fingerprint", it) }
        }

    private fun rejectUnknownKeys(
        node: ObjectNode,
        allowed: Set<String>,
        label: String,
    ) {
        val unknown = node.fieldNames().asSequence().firstOrNull { it !in allowed }
        if (unknown != null) {
            throw invalid("Unknown $label field \"$unknown\".")
        }
    }

    private fun optionalNonEmptyText(
        node: ObjectNode,
        field: String,
    ): String? =
        if (node.has(field)) {
            requireText(node[field], "base.$field", nonEmpty = true)
        } else {
            null
        }

    private fun requireText(
        node: JsonNode?,
        field: String,
        nonEmpty: Boolean = false,
    ): String {
        if (node == null || !node.isTextual) {
            throw invalid("Expected $field to be a string.")
        }
        val value = node.asText()
        if (nonEmpty && value.isBlank()) {
            throw invalid("Expected $field to be non-empty.")
        }
        return value
    }

    private fun requireSafeInteger(
        node: JsonNode?,
        field: String,
        min: Long? = null,
    ): Long {
        if (node == null || !node.isIntegralNumber || node.bigIntegerValue().abs() > maxSafeInteger) {
            throw invalid("Expected $field to be a JavaScript-safe integer.")
        }
        val value = node.longValue()
        if (min != null && value < min) {
            throw invalid("Expected $field to be >= $min.")
        }
        return value
    }

    private fun invalid(
        message: String,
        cause: Throwable? = null,
    ): StudioProjectStoreException =
        StudioProjectStoreException(
            StudioProjectStoreErrorCode.INVALID_PROJECT,
            message,
            cause,
        )
}
