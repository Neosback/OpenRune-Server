package dev.openrune.studio.format

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigInteger

public object StudioFormatV1Codec {
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val maxSafeInteger: BigInteger = BigInteger.valueOf(9_007_199_254_740_991L)

    private val batchKeys = setOf("format", "version", "id", "createdAt", "transactions")
    private val transactionKeys =
        setOf("id", "label", "source", "timestamp", "mutations", "affectedMaps", "tileCount")
    private val tileMutationKeys =
        setOf("kind", "mapX", "mapY", "level", "localX", "localY", "before", "after")
    private val objectMutationKeys = setOf("kind", "mapX", "mapY", "level", "before", "after")
    private val tileSnapshotKeys = setOf("h", "hl", "u", "o", "s", "r", "f")
    private val locKeys = setOf("id", "flags", "worldX", "worldY")
    private val mapRefKeys = setOf("x", "y")
    private val projectKeys =
        setOf("format", "version", "id", "name", "createdAt", "updatedAt", "base", "edits")
    private val baseKeys = setOf("kind", "game", "revision", "profileId", "name", "fingerprint")

    public fun decodeEditBatch(input: String): EditBatchV1 {
        val node = parseJson(input, "Edit Format v1")
        val issues = validateEditBatch(node)
        if (issues.isNotEmpty()) {
            throw StudioFormatException("Invalid Edit Format v1 document.", issues)
        }
        return parseEditBatch(node as ObjectNode)
    }

    public fun encodeEditBatch(batch: EditBatchV1, pretty: Boolean = true): String {
        val node = editBatchNode(batch)
        val issues = validateEditBatch(node)
        if (issues.isNotEmpty()) {
            throw StudioFormatException("Cannot encode invalid Edit Format v1 document.", issues)
        }
        return writeJson(node, pretty)
    }

    public fun decodeProject(input: String): ProjectV1 {
        val node = parseJson(input, "Project v1")
        val issues = validateProject(node)
        if (issues.isNotEmpty()) {
            throw StudioFormatException("Invalid Project v1 document.", issues)
        }
        return parseProject(node as ObjectNode)
    }

    public fun encodeProject(project: ProjectV1, pretty: Boolean = true): String {
        val node = projectNode(project)
        val issues = validateProject(node)
        if (issues.isNotEmpty()) {
            throw StudioFormatException("Cannot encode invalid Project v1 document.", issues)
        }
        return writeJson(node, pretty)
    }

    public fun validateEditBatch(node: JsonNode): List<StudioFormatIssue> {
        val issues = mutableListOf<StudioFormatIssue>()
        validateEditBatch(node, "$", issues)
        return issues
    }

    public fun validateProject(node: JsonNode): List<StudioFormatIssue> {
        val issues = mutableListOf<StudioFormatIssue>()
        val project = requireObject(node, "$", issues) ?: return issues

        checkExactKeys(project, projectKeys, "$", issues)
        requireLiteral(project["format"], PROJECT_FORMAT_V1_NAME, "$.format", issues)
        requireLiteral(project["version"], PROJECT_FORMAT_V1_VERSION.toLong(), "$.version", issues)
        requireString(project["id"], "$.id", issues, nonEmpty = true)
        requireString(project["name"], "$.name", issues, nonEmpty = true)
        val createdAt = requireInteger(project["createdAt"], "$.createdAt", issues, min = 0)
        val updatedAt = requireInteger(project["updatedAt"], "$.updatedAt", issues, min = 0)
        if (createdAt != null && updatedAt != null && updatedAt < createdAt) {
            issue(issues, "$.updatedAt", "Expected updatedAt >= createdAt.")
        }

        validateBase(project["base"], "$.base", issues)

        val editIssues = mutableListOf<StudioFormatIssue>()
        validateEditBatch(project["edits"], "$", editIssues)
        editIssues.forEach { editIssue ->
            issue(issues, "$.edits" + editIssue.path.removePrefix("$"), editIssue.message)
        }

        return issues
    }

    private fun validateEditBatch(
        node: JsonNode?,
        path: String,
        issues: MutableList<StudioFormatIssue>,
    ) {
        val batch = requireObject(node, path, issues) ?: return
        checkExactKeys(batch, batchKeys, path, issues)
        requireLiteral(batch["format"], EDIT_FORMAT_V1_NAME, "$path.format", issues)
        requireLiteral(batch["version"], EDIT_FORMAT_V1_VERSION.toLong(), "$path.version", issues)
        requireString(batch["id"], "$path.id", issues, nonEmpty = true)
        requireInteger(batch["createdAt"], "$path.createdAt", issues, min = 0)

        val transactions = batch["transactions"]
        if (transactions == null || !transactions.isArray) {
            issue(issues, "$path.transactions", "Expected transaction array.")
            return
        }
        transactions.forEachIndexed { index, transaction ->
            validateTransaction(transaction, "$path.transactions[$index]", issues)
        }
    }

    private fun validateTransaction(
        node: JsonNode,
        path: String,
        issues: MutableList<StudioFormatIssue>,
    ) {
        val transaction = requireObject(node, path, issues) ?: return
        checkExactKeys(transaction, transactionKeys, path, issues)
        requireString(transaction["id"], "$path.id", issues, nonEmpty = true)
        requireString(transaction["label"], "$path.label", issues, nonEmpty = true)

        val source = transaction["source"]
        if (source == null || !source.isTextual || EditTransactionSourceV1.fromWireName(source.asText()) == null) {
            issue(issues, "$path.source", "Unknown transaction source.")
        }

        requireInteger(transaction["timestamp"], "$path.timestamp", issues, min = 0)
        val tileCount = requireInteger(transaction["tileCount"], "$path.tileCount", issues, min = 0)

        val mutations = transaction["mutations"]
        val validMutations = mutableListOf<ObjectNode>()
        if (mutations == null || !mutations.isArray || mutations.isEmpty) {
            issue(issues, "$path.mutations", "Expected at least one mutation.")
        } else {
            mutations.forEachIndexed { index, mutation ->
                val localIssues = mutableListOf<StudioFormatIssue>()
                if (
                    validateMutation(mutation, "$path.mutations[$index]", localIssues) &&
                    localIssues.isEmpty()
                ) {
                    validMutations += mutation as ObjectNode
                }
                issues += localIssues
            }
        }

        val affectedMaps = transaction["affectedMaps"]
        val validMaps = mutableListOf<EditMapRefV1>()
        if (affectedMaps == null || !affectedMaps.isArray) {
            issue(issues, "$path.affectedMaps", "Expected map coordinate array.")
        } else {
            val seen = mutableSetOf<EditMapRefV1>()
            affectedMaps.forEachIndexed { index, mapNode ->
                val localIssues = mutableListOf<StudioFormatIssue>()
                val map = validateMapRef(mapNode, "$path.affectedMaps[$index]", localIssues)
                if (map != null) {
                    if (!seen.add(map)) {
                        issue(localIssues, "$path.affectedMaps[$index]", "Duplicate affected map.")
                    }
                    validMaps += map
                }
                issues += localIssues
            }
        }

        if (validMutations.isNotEmpty()) {
            val derivedMaps =
                validMutations
                    .map {
                        EditMapRefV1(
                            x = it["mapX"].intValue(),
                            y = it["mapY"].intValue(),
                        )
                    }.distinct()
            if (validMaps != derivedMaps) {
                issue(
                    issues,
                    "$path.affectedMaps",
                    "Must match mutation-derived maps in first-occurrence order.",
                )
            }
            val derivedTileCount = validMutations.count { it["kind"].asText() == "map.tile" }.toLong()
            if (tileCount != null && tileCount != derivedTileCount) {
                issue(issues, "$path.tileCount", "Expected derived tile count $derivedTileCount.")
            }
        }
    }

    private fun validateMutation(
        node: JsonNode,
        path: String,
        issues: MutableList<StudioFormatIssue>,
    ): Boolean {
        val mutation = requireObject(node, path, issues) ?: return false
        val kind = mutation["kind"]
        if (kind == null || !kind.isTextual) {
            issue(issues, "$path.kind", "Unknown mutation kind.")
            return false
        }

        when (kind.asText()) {
            "map.tile" -> {
                checkExactKeys(mutation, tileMutationKeys, path, issues)
                validateMapCoordinates(mutation, path, issues)
                requireInteger(mutation["localX"], "$path.localX", issues, min = 0, max = 63)
                requireInteger(mutation["localY"], "$path.localY", issues, min = 0, max = 63)
                validateTileSnapshot(mutation["before"], "$path.before", issues)
                validateTileSnapshot(mutation["after"], "$path.after", issues)
            }
            "map.objects" -> {
                checkExactKeys(mutation, objectMutationKeys, path, issues)
                validateMapCoordinates(mutation, path, issues)
                validateLocArray(mutation["before"], "$path.before", issues)
                validateLocArray(mutation["after"], "$path.after", issues)
            }
            else -> {
                issue(issues, "$path.kind", "Unknown mutation kind.")
                return false
            }
        }
        return true
    }

    private fun validateMapCoordinates(
        mutation: ObjectNode,
        path: String,
        issues: MutableList<StudioFormatIssue>,
    ) {
        requireInteger(mutation["mapX"], "$path.mapX", issues, min = 0, max = 255)
        requireInteger(mutation["mapY"], "$path.mapY", issues, min = 0, max = 255)
        requireInteger(mutation["level"], "$path.level", issues, min = 0, max = 3)
    }

    private fun validateTileSnapshot(
        node: JsonNode?,
        path: String,
        issues: MutableList<StudioFormatIssue>,
    ) {
        val snapshot = requireObject(node, path, issues) ?: return
        checkExactKeys(snapshot, tileSnapshotKeys, path, issues)
        for (key in listOf("h", "u", "o", "s", "r", "f")) {
            if (snapshot.has(key)) {
                requireInteger(snapshot[key], "$path.$key", issues)
            }
        }
        if (snapshot.has("hl")) {
            val heights = snapshot["hl"]
            if (!heights.isArray) {
                issue(issues, "$path.hl", "Expected integer array.")
            } else {
                heights.forEachIndexed { index, height ->
                    requireInteger(height, "$path.hl[$index]", issues)
                }
            }
        }
    }

    private fun validateLocArray(
        node: JsonNode?,
        path: String,
        issues: MutableList<StudioFormatIssue>,
    ) {
        if (node == null || !node.isArray) {
            issue(issues, path, "Expected loc array.")
            return
        }
        node.forEachIndexed { index, locNode ->
            val locPath = "$path[$index]"
            val loc = requireObject(locNode, locPath, issues) ?: return@forEachIndexed
            checkExactKeys(loc, locKeys, locPath, issues)
            requireInteger(loc["id"], "$locPath.id", issues, min = 0)
            requireInteger(loc["flags"], "$locPath.flags", issues)
            requireInteger(loc["worldX"], "$locPath.worldX", issues)
            requireInteger(loc["worldY"], "$locPath.worldY", issues)
        }
    }

    private fun validateMapRef(
        node: JsonNode,
        path: String,
        issues: MutableList<StudioFormatIssue>,
    ): EditMapRefV1? {
        val map = requireObject(node, path, issues) ?: return null
        checkExactKeys(map, mapRefKeys, path, issues)
        val x = requireInteger(map["x"], "$path.x", issues, min = 0, max = 255)
        val y = requireInteger(map["y"], "$path.y", issues, min = 0, max = 255)
        return if (x != null && y != null) EditMapRefV1(x.toInt(), y.toInt()) else null
    }

    private fun validateBase(
        node: JsonNode?,
        path: String,
        issues: MutableList<StudioFormatIssue>,
    ) {
        val base = requireObject(node, path, issues) ?: return
        checkExactKeys(base, baseKeys, path, issues)
        requireLiteral(base["kind"], "cache", "$path.kind", issues)
        requireString(base["game"], "$path.game", issues, nonEmpty = true)
        requireInteger(base["revision"], "$path.revision", issues, min = 0)
        for (key in listOf("profileId", "name", "fingerprint")) {
            if (base.has(key)) {
                requireString(base[key], "$path.$key", issues, nonEmpty = true)
            }
        }
    }

    private fun parseEditBatch(node: ObjectNode): EditBatchV1 =
        EditBatchV1(
            id = node["id"].asText(),
            createdAt = node["createdAt"].longValue(),
            transactions = node["transactions"].map { parseTransaction(it as ObjectNode) },
        )

    private fun parseTransaction(node: ObjectNode): EditTransactionV1 =
        EditTransactionV1(
            id = node["id"].asText(),
            label = node["label"].asText(),
            source = requireNotNull(EditTransactionSourceV1.fromWireName(node["source"].asText())),
            timestamp = node["timestamp"].longValue(),
            mutations = node["mutations"].map { parseMutation(it as ObjectNode) },
            affectedMaps =
                node["affectedMaps"].map {
                    EditMapRefV1(it["x"].intValue(), it["y"].intValue())
                },
            tileCount = node["tileCount"].longValue(),
        )

    private fun parseMutation(node: ObjectNode): EditMutationV1 =
        when (node["kind"].asText()) {
            "map.tile" ->
                EditTileMutationV1(
                    mapX = node["mapX"].intValue(),
                    mapY = node["mapY"].intValue(),
                    level = node["level"].intValue(),
                    localX = node["localX"].intValue(),
                    localY = node["localY"].intValue(),
                    before = parseTileSnapshot(node["before"] as ObjectNode),
                    after = parseTileSnapshot(node["after"] as ObjectNode),
                )
            "map.objects" ->
                EditObjectMutationV1(
                    mapX = node["mapX"].intValue(),
                    mapY = node["mapY"].intValue(),
                    level = node["level"].intValue(),
                    before = node["before"].map(::parseLoc),
                    after = node["after"].map(::parseLoc),
                )
            else -> error("Validated mutation had unknown kind.")
        }

    private fun parseTileSnapshot(node: ObjectNode): EditTileSnapshotV1 =
        EditTileSnapshotV1(
            h = node.optionalLong("h"),
            hl = node["hl"]?.map { it.longValue() },
            u = node.optionalLong("u"),
            o = node.optionalLong("o"),
            s = node.optionalLong("s"),
            r = node.optionalLong("r"),
            f = node.optionalLong("f"),
        )

    private fun parseLoc(node: JsonNode): EditLocV1 =
        EditLocV1(
            id = node["id"].longValue(),
            flags = node["flags"].longValue(),
            worldX = node["worldX"].longValue(),
            worldY = node["worldY"].longValue(),
        )

    private fun parseProject(node: ObjectNode): ProjectV1 {
        val base = node["base"]
        return ProjectV1(
            id = node["id"].asText(),
            name = node["name"].asText(),
            createdAt = node["createdAt"].longValue(),
            updatedAt = node["updatedAt"].longValue(),
            base =
                ProjectCacheIdentityV1(
                    game = base["game"].asText(),
                    revision = base["revision"].longValue(),
                    profileId = base.optionalText("profileId"),
                    name = base.optionalText("name"),
                    fingerprint = base.optionalText("fingerprint"),
                ),
            edits = parseEditBatch(node["edits"] as ObjectNode),
        )
    }

    private fun editBatchNode(batch: EditBatchV1): ObjectNode =
        mapper.createObjectNode().apply {
            put("format", batch.format)
            put("version", batch.version)
            put("id", batch.id)
            put("createdAt", batch.createdAt)
            set<ArrayNode>(
                "transactions",
                mapper.createArrayNode().apply {
                    batch.transactions.forEach { add(transactionNode(it)) }
                },
            )
        }

    private fun transactionNode(transaction: EditTransactionV1): ObjectNode =
        mapper.createObjectNode().apply {
            put("id", transaction.id)
            put("label", transaction.label)
            put("source", transaction.source.wireName)
            put("timestamp", transaction.timestamp)
            set<ArrayNode>(
                "mutations",
                mapper.createArrayNode().apply {
                    transaction.mutations.forEach { add(mutationNode(it)) }
                },
            )
            set<ArrayNode>(
                "affectedMaps",
                mapper.createArrayNode().apply {
                    transaction.affectedMaps.forEach { map ->
                        add(mapper.createObjectNode().put("x", map.x).put("y", map.y))
                    }
                },
            )
            put("tileCount", transaction.tileCount)
        }

    private fun mutationNode(mutation: EditMutationV1): ObjectNode =
        mapper.createObjectNode().apply {
            when (mutation) {
                is EditTileMutationV1 -> {
                    put("kind", "map.tile")
                    put("mapX", mutation.mapX)
                    put("mapY", mutation.mapY)
                    put("level", mutation.level)
                    put("localX", mutation.localX)
                    put("localY", mutation.localY)
                    set<ObjectNode>("before", tileSnapshotNode(mutation.before))
                    set<ObjectNode>("after", tileSnapshotNode(mutation.after))
                }
                is EditObjectMutationV1 -> {
                    put("kind", "map.objects")
                    put("mapX", mutation.mapX)
                    put("mapY", mutation.mapY)
                    put("level", mutation.level)
                    set<ArrayNode>("before", locArrayNode(mutation.before))
                    set<ArrayNode>("after", locArrayNode(mutation.after))
                }
            }
        }

    private fun tileSnapshotNode(snapshot: EditTileSnapshotV1): ObjectNode =
        mapper.createObjectNode().apply {
            snapshot.h?.let { put("h", it) }
            snapshot.hl?.let { values ->
                set<ArrayNode>("hl", mapper.createArrayNode().apply { values.forEach { add(it) } })
            }
            snapshot.u?.let { put("u", it) }
            snapshot.o?.let { put("o", it) }
            snapshot.s?.let { put("s", it) }
            snapshot.r?.let { put("r", it) }
            snapshot.f?.let { put("f", it) }
        }

    private fun locArrayNode(locs: List<EditLocV1>): ArrayNode =
        mapper.createArrayNode().apply {
            locs.forEach { loc ->
                add(
                    mapper
                        .createObjectNode()
                        .put("id", loc.id)
                        .put("flags", loc.flags)
                        .put("worldX", loc.worldX)
                        .put("worldY", loc.worldY),
                )
            }
        }

    private fun projectNode(project: ProjectV1): ObjectNode =
        mapper.createObjectNode().apply {
            put("format", project.format)
            put("version", project.version)
            put("id", project.id)
            put("name", project.name)
            put("createdAt", project.createdAt)
            put("updatedAt", project.updatedAt)
            set<ObjectNode>(
                "base",
                mapper.createObjectNode().apply {
                    put("kind", project.base.kind)
                    put("game", project.base.game)
                    put("revision", project.base.revision)
                    project.base.profileId?.let { put("profileId", it) }
                    project.base.name?.let { put("name", it) }
                    project.base.fingerprint?.let { put("fingerprint", it) }
                },
            )
            set<ObjectNode>("edits", editBatchNode(project.edits))
        }

    private fun parseJson(input: String, label: String): JsonNode =
        try {
            mapper.readTree(input)
                ?: throw StudioFormatException("Invalid " + label + " JSON: empty document.")
        } catch (exception: JsonProcessingException) {
            throw StudioFormatException(
                "Invalid " + label + " JSON: " + exception.originalMessage,
                cause = exception,
            )
        }

    private fun writeJson(node: JsonNode, pretty: Boolean): String =
        if (pretty) {
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node)
        } else {
            mapper.writeValueAsString(node)
        }

    private fun requireObject(
        node: JsonNode?,
        path: String,
        issues: MutableList<StudioFormatIssue>,
    ): ObjectNode? {
        if (node == null || !node.isObject) {
            issue(issues, path, "Expected object.")
            return null
        }
        return node as ObjectNode
    }

    private fun checkExactKeys(
        node: ObjectNode,
        allowed: Set<String>,
        path: String,
        issues: MutableList<StudioFormatIssue>,
    ) {
        node.fieldNames().forEachRemaining { key ->
            if (key !in allowed) {
                issue(issues, "$path.$key", "Unknown field.")
            }
        }
    }

    private fun requireString(
        node: JsonNode?,
        path: String,
        issues: MutableList<StudioFormatIssue>,
        nonEmpty: Boolean = false,
    ): String? {
        if (node == null || !node.isTextual) {
            issue(issues, path, "Expected string.")
            return null
        }
        val value = node.asText()
        if (nonEmpty && value.isBlank()) {
            issue(issues, path, "Expected non-empty string.")
            return null
        }
        return value
    }

    private fun requireInteger(
        node: JsonNode?,
        path: String,
        issues: MutableList<StudioFormatIssue>,
        min: Long? = null,
        max: Long? = null,
    ): Long? {
        if (node == null || !node.isIntegralNumber || node.bigIntegerValue().abs() > maxSafeInteger) {
            issue(issues, path, "Expected safe integer.")
            return null
        }
        val value = node.longValue()
        if (min != null && value < min) {
            issue(issues, path, "Expected value >= $min.")
            return null
        }
        if (max != null && value > max) {
            issue(issues, path, "Expected value <= $max.")
            return null
        }
        return value
    }

    private fun requireLiteral(
        node: JsonNode?,
        expected: String,
        path: String,
        issues: MutableList<StudioFormatIssue>,
    ) {
        if (node == null || !node.isTextual || node.asText() != expected) {
            issue(issues, path, "Expected \"$expected\".")
        }
    }

    private fun requireLiteral(
        node: JsonNode?,
        expected: Long,
        path: String,
        issues: MutableList<StudioFormatIssue>,
    ) {
        if (node == null || !node.isIntegralNumber || node.bigIntegerValue() != BigInteger.valueOf(expected)) {
            issue(issues, path, "Expected version $expected.")
        }
    }

    private fun issue(
        issues: MutableList<StudioFormatIssue>,
        path: String,
        message: String,
    ) {
        issues += StudioFormatIssue(path, message)
    }

    private fun JsonNode.optionalLong(field: String): Long? =
        get(field)?.takeUnless(JsonNode::isNull)?.longValue()

    private fun JsonNode.optionalText(field: String): String? =
        get(field)?.takeUnless(JsonNode::isNull)?.asText()
}
