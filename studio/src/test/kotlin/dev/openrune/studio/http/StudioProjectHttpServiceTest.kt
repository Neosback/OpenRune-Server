package dev.openrune.studio.http

import dev.openrune.studio.format.ProjectV1
import dev.openrune.studio.format.StudioFormatV1Codec
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StudioProjectHttpServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private val client: HttpClient = HttpClient.newHttpClient()

    @Test
    fun `disabled service does not bind or write a session descriptor`() =
        runBlocking {
            val config =
                StudioProjectHttpConfig(
                    enabled = false,
                    port = 0,
                    projectRoot = tempDir.resolve("projects"),
                    sessionFile = tempDir.resolve("session.json"),
                    token = "disabled-token",
                )
            val service = StudioProjectHttpService(config)

            service.startup()
            try {
                assertEquals(null, service.endpoint)
                assertFalse(Files.exists(config.sessionFile))
            } finally {
                service.shutdown()
            }
        }

    @Test
    fun `health is loopback readable but projects require the session token`() =
        withService { service, config ->
            val health = request(service, "/health")
            assertEquals(200, health.statusCode())
            assertTrue(health.body().contains("\"status\":\"ok\""))

            val projects = request(service, "/projects")
            assertEquals(401, projects.statusCode())
            assertEquals("Bearer", projects.headers().firstValue("WWW-Authenticate").orElse(null))

            val authorized = request(service, "/projects", token = config.token)
            assertEquals(200, authorized.statusCode())
            assertEquals("[]", authorized.body())
        }

    @Test
    fun `non-loopback Host headers are rejected before routing`() =
        withService { service, _ ->
            val endpoint = requireNotNull(service.endpoint)
            Socket("127.0.0.1", endpoint.port).use { socket ->
                socket.soTimeout = 2_000
                val request =
                    "GET /studio/v1/health HTTP/1.1\r\n" +
                        "Host: evil.example\r\n" +
                        "Connection: close\r\n\r\n"
                socket.getOutputStream().use { output ->
                    output.write(request.toByteArray(StandardCharsets.US_ASCII))
                    output.flush()
                }
                val statusLine = socket.getInputStream().bufferedReader().readLine()
                assertTrue(statusLine.contains(" 403 "))
            }
        }

    @Test
    fun `non-loopback browser origins are rejected and loopback preflight is allowed`() =
        withService { service, config ->
            val rejected =
                request(
                    service,
                    "/projects",
                    token = config.token,
                    origin = "https://evil.example",
                )
            assertEquals(403, rejected.statusCode())

            val preflight =
                request(
                    service,
                    "/projects",
                    method = "OPTIONS",
                    origin = "http://localhost:5173",
                )
            assertEquals(204, preflight.statusCode())
            assertEquals(
                "http://localhost:5173",
                preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(null),
            )
            assertTrue(
                preflight.headers().firstValue("Access-Control-Allow-Headers").orElse("")
                    .contains("Authorization"),
            )
        }

    @Test
    fun `project CRUD export and encoded ids stay behind the authenticated API`() =
        withService { service, config ->
            val unsafeId = "../../outside/evil"
            val createBody =
                """
                {
                  "id": "$unsafeId",
                  "name": "API project",
                  "base": {
                    "kind": "cache",
                    "game": "oldschool",
                    "revision": 240,
                    "fingerprint": "fixture:osrs-240"
                  }
                }
                """.trimIndent()

            val created =
                request(
                    service,
                    "/projects",
                    method = "POST",
                    token = config.token,
                    body = createBody,
                )
            assertEquals(201, created.statusCode())
            val project = StudioFormatV1Codec.decodeProject(created.body())
            assertEquals(unsafeId, project.id)
            assertEquals("API project", project.name)
            assertFalse(Files.exists(tempDir.resolve("outside")))

            val list = request(service, "/projects", token = config.token)
            assertEquals(200, list.statusCode())
            assertTrue(list.body().contains("\"id\":\"../../outside/evil\""))

            val encodedId = encodePathSegment(unsafeId)
            val loaded = request(service, "/projects/$encodedId", token = config.token)
            assertEquals(200, loaded.statusCode())
            assertEquals(project, StudioFormatV1Codec.decodeProject(loaded.body()))

            val renamed = project.copy(name = "Renamed", updatedAt = project.updatedAt + 1)
            val saved =
                request(
                    service,
                    "/projects/$encodedId",
                    method = "PUT",
                    token = config.token,
                    body = StudioFormatV1Codec.encodeProject(renamed),
                )
            assertEquals(204, saved.statusCode())

            val exported =
                request(
                    service,
                    "/projects/$encodedId/export",
                    token = config.token,
                )
            assertEquals(200, exported.statusCode())
            assertEquals(renamed, StudioFormatV1Codec.decodeProject(exported.body()))

            val deleted =
                request(
                    service,
                    "/projects/$encodedId",
                    method = "DELETE",
                    token = config.token,
                )
            assertEquals(204, deleted.statusCode())
            assertEquals(
                404,
                request(service, "/projects/$encodedId", token = config.token).statusCode(),
            )
        }

    @Test
    fun `import preserves portable Project Format and duplicate ids return conflict`() =
        withService { service, config ->
            val project =
                StudioFormatV1Codec.decodeProject(
                    fixture("project-v1.golden.json"),
                )

            val imported =
                request(
                    service,
                    "/projects/import",
                    method = "POST",
                    token = config.token,
                    body = StudioFormatV1Codec.encodeProject(project),
                )
            assertEquals(201, imported.statusCode())
            assertEquals(project, StudioFormatV1Codec.decodeProject(imported.body()))

            val conflict =
                request(
                    service,
                    "/projects/import",
                    method = "POST",
                    token = config.token,
                    body = StudioFormatV1Codec.encodeProject(project),
                )
            assertEquals(409, conflict.statusCode())
            assertTrue(conflict.body().contains("\"error\":\"conflict\""))
        }

    @Test
    fun `put rejects a body whose project id differs from the route id`() =
        withService { service, config ->
            val created =
                createProject(
                    service = service,
                    token = config.token,
                    id = "project-a",
                )
            val mismatched = created.copy(id = "project-b")

            val response =
                request(
                    service,
                    "/projects/project-a",
                    method = "PUT",
                    token = config.token,
                    body = StudioFormatV1Codec.encodeProject(mismatched),
                )

            assertEquals(400, response.statusCode())
            assertTrue(response.body().contains("\"error\":\"invalid_project\""))
        }

    @Test
    fun `blank create id falls back to a generated id`() =
        withService { service, config ->
            val response =
                request(
                    service,
                    "/projects",
                    method = "POST",
                    token = config.token,
                    body =
                        """
                        {
                          "id": "   ",
                          "name": "Generated",
                          "base": {
                            "kind": "cache",
                            "game": "oldschool",
                            "revision": 240
                          }
                        }
                        """.trimIndent(),
                )

            assertEquals(201, response.statusCode())
            val project = StudioFormatV1Codec.decodeProject(response.body())
            assertTrue(project.id.isNotBlank())
        }

    @Test
    fun `session descriptor contains the bound endpoint and token and is removed on shutdown`() =
        runBlocking {
            val config = config()
            val service = StudioProjectHttpService(config)
            service.startup()

            val endpoint = requireNotNull(service.endpoint)
            try {
                assertTrue(Files.exists(config.sessionFile))
                val descriptor = Files.readString(config.sessionFile)
                assertTrue(descriptor.contains(endpoint.toString()))
                assertTrue(descriptor.contains(config.token))
            } finally {
                service.shutdown()
            }

            assertFalse(Files.exists(config.sessionFile))
        }

    @Test
    fun `environment config stays disabled by default and accepts explicit opt-in`() {
        val defaults =
            StudioProjectHttpConfig.fromEnvironment(
                environment = emptyMap(),
                properties = { null },
            )
        assertFalse(defaults.enabled)
        assertEquals(8765, defaults.port)

        val configured =
            StudioProjectHttpConfig.fromEnvironment(
                environment =
                    mapOf(
                        "OPENRUNE_STUDIO_ENABLED" to "true",
                        "OPENRUNE_STUDIO_PORT" to "9876",
                        "OPENRUNE_STUDIO_PROJECT_ROOT" to tempDir.resolve("custom-projects").toString(),
                        "OPENRUNE_STUDIO_SESSION_FILE" to tempDir.resolve("custom-session.json").toString(),
                    ),
                properties = { null },
            )

        assertTrue(configured.enabled)
        assertEquals(9876, configured.port)
        assertEquals(tempDir.resolve("custom-projects"), configured.projectRoot)
        assertEquals(tempDir.resolve("custom-session.json"), configured.sessionFile)
    }

    private fun createProject(
        service: StudioProjectHttpService,
        token: String,
        id: String,
    ): ProjectV1 {
        val response =
            request(
                service,
                "/projects",
                method = "POST",
                token = token,
                body =
                    """
                    {
                      "id": "$id",
                      "name": "Project",
                      "base": {
                        "kind": "cache",
                        "game": "oldschool",
                        "revision": 240
                      }
                    }
                    """.trimIndent(),
            )
        assertEquals(201, response.statusCode())
        return StudioFormatV1Codec.decodeProject(response.body())
    }

    private fun withService(
        block: (StudioProjectHttpService, StudioProjectHttpConfig) -> Unit,
    ) = runBlocking {
        val config = config()
        val service = StudioProjectHttpService(config)
        service.startup()
        try {
            block(service, config)
        } finally {
            service.shutdown()
        }
    }

    private fun config(): StudioProjectHttpConfig =
        StudioProjectHttpConfig(
            enabled = true,
            port = 0,
            projectRoot = tempDir.resolve("projects-" + System.nanoTime()),
            sessionFile = tempDir.resolve("session-" + System.nanoTime() + ".json"),
            token = "test-session-token",
        )

    private fun request(
        service: StudioProjectHttpService,
        path: String,
        method: String = "GET",
        token: String? = null,
        origin: String? = null,
        body: String? = null,
    ): HttpResponse<String> {
        val endpoint = requireNotNull(service.endpoint)
        val builder =
            HttpRequest.newBuilder(
                URI.create(endpoint.toString() + path),
            )
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        if (origin != null) {
            builder.header("Origin", origin)
        }
        val publisher =
            if (body == null) {
                HttpRequest.BodyPublishers.noBody()
            } else {
                builder.header("Content-Type", "application/json")
                HttpRequest.BodyPublishers.ofString(body)
            }
        builder.method(method, publisher)
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun fixture(name: String): String =
        requireNotNull(
            javaClass.getResource("/openrune-studio-contract/$name"),
        ).readText()
}
