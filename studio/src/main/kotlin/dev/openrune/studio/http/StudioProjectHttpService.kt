package dev.openrune.studio.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.openrune.studio.format.StudioFormatException
import dev.openrune.studio.format.StudioFormatV1Codec
import dev.openrune.studio.project.FileSystemStudioProjectStore
import dev.openrune.studio.project.StudioProjectStore
import dev.openrune.studio.project.StudioProjectStoreErrorCode
import dev.openrune.studio.project.StudioProjectStoreException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.rsmod.server.services.Service

public data class StudioProjectHttpConfig(
    val enabled: Boolean = false,
    val port: Int = 8765,
    val projectRoot: Path = Paths.get(".data", "studio", "projects"),
    val sessionFile: Path = Paths.get(".data", "studio", "session.json"),
    val token: String = generateSessionToken(),
) {
    init {
        require(port in 0..65535) { "Studio HTTP port must be between 0 and 65535." }
        require(token.isNotBlank()) { "Studio HTTP token must not be blank." }
    }

    public companion object {
        public fun fromEnvironment(
            environment: Map<String, String> = System.getenv(),
            properties: (String) -> String? = System::getProperty,
        ): StudioProjectHttpConfig {
            val enabled =
                properties("openrune.studio.enabled")?.toBooleanStrictOrNull()
                    ?: environment["OPENRUNE_STUDIO_ENABLED"]?.toBooleanStrictOrNull()
                    ?: false
            val port =
                properties("openrune.studio.port")?.toIntOrNull()
                    ?: environment["OPENRUNE_STUDIO_PORT"]?.toIntOrNull()
                    ?: 8765
            val projectRoot =
                properties("openrune.studio.projectRoot")
                    ?: environment["OPENRUNE_STUDIO_PROJECT_ROOT"]
            val sessionFile =
                properties("openrune.studio.sessionFile")
                    ?: environment["OPENRUNE_STUDIO_SESSION_FILE"]

            return StudioProjectHttpConfig(
                enabled = enabled,
                port = port,
                projectRoot = projectRoot?.let(Paths::get) ?: Paths.get(".data", "studio", "projects"),
                sessionFile = sessionFile?.let(Paths::get) ?: Paths.get(".data", "studio", "session.json"),
            )
        }

        private fun generateSessionToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}

public class StudioProjectHttpService(
    private val config: StudioProjectHttpConfig,
    private val store: StudioProjectStore,
) : Service {
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    public constructor() : this(StudioProjectHttpConfig.fromEnvironment())

    public constructor(config: StudioProjectHttpConfig) :
        this(
            config = config,
            store = FileSystemStudioProjectStore(config.projectRoot),
        )

    public val endpoint: URI?
        get() {
            val address = server?.address ?: return null
            return URI("http", null, LOOPBACK_HOST, address.port, API_PREFIX.removeSuffix("/"), null, null)
        }

    override suspend fun startup() {
        if (!config.enabled) {
            return
        }
        check(server == null) { "Studio project HTTP service is already running." }

        val httpServer = HttpServer.create(InetSocketAddress(LOOPBACK_HOST, config.port), 0)
        val httpExecutor =
            Executors.newFixedThreadPool(4) { runnable ->
                Thread(runnable, "openrune-studio-http").apply { isDaemon = true }
            }
        httpServer.executor = httpExecutor
        httpServer.createContext(API_PREFIX) { exchange -> handle(exchange) }

        try {
            httpServer.start()
            server = httpServer
            executor = httpExecutor
            writeSessionDescriptor()
        } catch (throwable: Throwable) {
            httpServer.stop(0)
            httpExecutor.shutdownNow()
            throw throwable
        }
    }

    override suspend fun shutdown() {
        server?.stop(0)
        server = null
        executor?.shutdownNow()
        executor = null
        runCatching { Files.deleteIfExists(config.sessionFile) }
    }

    private fun handle(exchange: HttpExchange) {
        try {
            if (!isLoopbackHost(exchange.requestHeaders.getFirst("Host"))) {
                sendError(exchange, 403, "forbidden_host", "Host must resolve to loopback.")
                return
            }

            val origin = exchange.requestHeaders.getFirst("Origin")
            if (origin != null && !isLoopbackOrigin(origin)) {
                sendError(exchange, 403, "forbidden_origin", "Origin must resolve to loopback.")
                return
            }
            applyCors(exchange, origin)

            if (exchange.requestMethod.equals("OPTIONS", ignoreCase = true)) {
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
                return
            }

            if (exchange.requestURI.rawPath == API_PREFIX + "health") {
                if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
                    methodNotAllowed(exchange, "GET")
                    return
                }
                sendJson(exchange, 200, "{\"status\":\"ok\",\"version\":1}")
                return
            }

            if (!hasValidToken(exchange)) {
                exchange.responseHeaders.set("WWW-Authenticate", "Bearer")
                sendError(exchange, 401, "unauthorized", "A valid Studio session token is required.")
                return
            }

            routeAuthenticated(exchange)
        } catch (exception: RequestBodyTooLargeException) {
            sendError(exchange, 413, "payload_too_large", exception.message ?: "Request body is too large.")
        } catch (exception: StudioProjectStoreException) {
            sendStoreError(exchange, exception)
        } catch (exception: StudioFormatException) {
            sendError(exchange, 400, "invalid_project", exception.message ?: "Invalid project document.")
        } catch (exception: Throwable) {
            sendError(exchange, 500, "internal_error", "Studio project request failed.")
        } finally {
            exchange.close()
        }
    }

    private fun routeAuthenticated(exchange: HttpExchange) {
        val rawPath = exchange.requestURI.rawPath
        if (rawPath == API_PREFIX + "projects") {
            when {
                exchange.requestMethod.equals("GET", ignoreCase = true) ->
                    sendJson(exchange, 200, StudioProjectApiCodec.encodeSummaries(store.listProjects()))
                exchange.requestMethod.equals("POST", ignoreCase = true) -> {
                    val input = StudioProjectApiCodec.decodeCreateProject(readBody(exchange))
                    val project = store.createProject(input)
                    sendJson(exchange, 201, StudioFormatV1Codec.encodeProject(project))
                }
                else -> methodNotAllowed(exchange, "GET, POST")
            }
            return
        }

        if (rawPath == API_PREFIX + "projects/import") {
            if (!exchange.requestMethod.equals("POST", ignoreCase = true)) {
                methodNotAllowed(exchange, "POST")
                return
            }
            val project = store.importProject(readBody(exchange))
            sendJson(exchange, 201, StudioFormatV1Codec.encodeProject(project))
            return
        }

        val prefix = API_PREFIX + "projects/"
        if (!rawPath.startsWith(prefix)) {
            sendError(exchange, 404, "not_found", "Studio API route was not found.")
            return
        }

        val rawRemainder = rawPath.removePrefix(prefix)
        val exportSuffix = "/export"
        val isExport = rawRemainder.endsWith(exportSuffix)
        val rawId =
            if (isExport) {
                rawRemainder.removeSuffix(exportSuffix)
            } else {
                rawRemainder
            }
        if (rawId.isBlank() || (!isExport && rawId.contains('/'))) {
            sendError(exchange, 404, "not_found", "Studio API route was not found.")
            return
        }

        val id = decodePathSegment(rawId)
        if (isExport) {
            if (!exchange.requestMethod.equals("GET", ignoreCase = true)) {
                methodNotAllowed(exchange, "GET")
                return
            }
            sendJson(exchange, 200, store.exportProject(id))
            return
        }

        when {
            exchange.requestMethod.equals("GET", ignoreCase = true) -> {
                val project = store.loadProject(id)
                if (project == null) {
                    sendError(exchange, 404, "not_found", "Project \"$id\" was not found.")
                    return
                }
                sendJson(exchange, 200, StudioFormatV1Codec.encodeProject(project))
            }
            exchange.requestMethod.equals("PUT", ignoreCase = true) -> {
                val project = StudioFormatV1Codec.decodeProject(readBody(exchange))
                if (project.id != id) {
                    sendError(
                        exchange,
                        400,
                        "invalid_project",
                        "Project id in the request body must match the route id.",
                    )
                    return
                }
                store.saveProject(project)
                exchange.sendResponseHeaders(204, -1)
            }
            exchange.requestMethod.equals("DELETE", ignoreCase = true) -> {
                store.deleteProject(id)
                exchange.sendResponseHeaders(204, -1)
            }
            else -> methodNotAllowed(exchange, "GET, PUT, DELETE")
        }
    }

    private fun hasValidToken(exchange: HttpExchange): Boolean {
        val authorization = exchange.requestHeaders.getFirst("Authorization") ?: return false
        val separator = authorization.indexOf(' ')
        if (separator <= 0 || !authorization.substring(0, separator).equals("Bearer", ignoreCase = true)) {
            return false
        }
        val candidate = authorization.substring(separator + 1).trim()
        if (candidate.isEmpty()) {
            return false
        }
        return MessageDigest.isEqual(
            config.token.toByteArray(StandardCharsets.UTF_8),
            candidate.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun readBody(exchange: HttpExchange): String {
        val bytes = exchange.requestBody.use { input -> input.readNBytes(MAX_BODY_BYTES + 1) }
        if (bytes.size > MAX_BODY_BYTES) {
            throw RequestBodyTooLargeException("Studio request bodies are limited to $MAX_BODY_BYTES bytes.")
        }
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun methodNotAllowed(
        exchange: HttpExchange,
        allowed: String,
    ) {
        exchange.responseHeaders.set("Allow", allowed)
        sendError(exchange, 405, "method_not_allowed", "HTTP method is not allowed for this route.")
    }

    private fun sendStoreError(
        exchange: HttpExchange,
        exception: StudioProjectStoreException,
    ) {
        val status =
            when (exception.code) {
                StudioProjectStoreErrorCode.INVALID_PROJECT -> 400
                StudioProjectStoreErrorCode.CONFLICT -> 409
                StudioProjectStoreErrorCode.NOT_FOUND -> 404
                StudioProjectStoreErrorCode.STORAGE_FAILED -> 500
            }
        sendError(
            exchange,
            status,
            exception.code.name.lowercase(),
            exception.message ?: "Studio project store request failed.",
        )
    }

    private fun sendError(
        exchange: HttpExchange,
        status: Int,
        code: String,
        message: String,
    ) {
        sendJson(exchange, status, StudioProjectApiCodec.encodeError(code, message))
    }

    private fun sendJson(
        exchange: HttpExchange,
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { output -> output.write(bytes) }
    }

    private fun applyCors(
        exchange: HttpExchange,
        origin: String?,
    ) {
        if (origin != null) {
            exchange.responseHeaders.set("Access-Control-Allow-Origin", origin)
            exchange.responseHeaders.set("Vary", "Origin")
        }
        exchange.responseHeaders.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        exchange.responseHeaders.set("Access-Control-Allow-Headers", "Authorization, Content-Type")
        exchange.responseHeaders.set("Access-Control-Max-Age", "600")
    }

    private fun writeSessionDescriptor() {
        val currentEndpoint = requireNotNull(endpoint)
        val payload =
            StudioProjectApiCodec.encodeSession(
                endpoint = currentEndpoint.toString(),
                token = config.token,
            )
        config.sessionFile.parent?.let { Files.createDirectories(it) }
        val temp =
            Files.createTempFile(
                config.sessionFile.parent ?: Paths.get("."),
                ".openrune-studio-session-",
                ".tmp",
            )
        try {
            Files.writeString(temp, payload, StandardCharsets.UTF_8)
            restrictToOwner(temp)
            try {
                Files.move(
                    temp,
                    config.sessionFile,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(
                    temp,
                    config.sessionFile,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
            restrictToOwner(config.sessionFile)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun restrictToOwner(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
            )
        }
    }

    private fun decodePathSegment(raw: String): String =
        URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8)

    private fun isLoopbackHost(header: String?): Boolean {
        if (header.isNullOrBlank()) {
            return false
        }
        val host =
            runCatching {
                URI("http://$header").host?.lowercase()
            }.getOrNull() ?: return false
        return host == "localhost" || host == LOOPBACK_HOST || host == "::1"
    }

    private fun isLoopbackOrigin(origin: String): Boolean {
        val uri = runCatching { URI(origin) }.getOrNull() ?: return false
        if (uri.scheme != "http" && uri.scheme != "https") {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        return host == "localhost" || host == LOOPBACK_HOST || host == "::1"
    }

    private class RequestBodyTooLargeException(message: String) : IOException(message)

    private companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val API_PREFIX = "/studio/v1/"
        private const val MAX_BODY_BYTES = 16 * 1024 * 1024
    }
}
