@file:Suppress("SpellCheckingInspection")

package crow.wasmline.gradle.server

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.gradle.api.logging.Logger
import java.io.File

/**
 * Lightweight HTTP server that serves wasmline build artifacts (.wlm, .wasm,
 * .cwasm, .pwasm, etc.) for remote plugin loading.
 *
 * The server runs in **foreground blocking mode** — the calling Gradle task
 * will block until the user interrupts the process (Ctrl+C).
 *
 * Date: 2026-06-05
 * Author: crowforkotlin
 */
internal object WasmlineHttpServer {

    /**
     * Start the HTTP server and serve files from [serveDirectory].
     *
     * Routes:
     * - `GET /` — HTML index page listing every downloadable file below [serveDirectory].
     * - `GET /{path...}` — download any file below [serveDirectory], including
     *   `artifacts/sha256/{prefix}/{digest}.{ext}` package objects.
     *
     * Every request path is resolved by [WasmlineArtifactServing.resolveServedFile],
     * which rejects `..` segments, absolute paths, and symlink escapes, and the
     * file is streamed with [LocalFileContent] instead of being read into heap.
     *
     * @param serveDirectory directory containing the build artifacts
     * @param host           bind address (default "127.0.0.1")
     * @param port           TCP port (default 8080)
     * @param logger         Gradle logger for startup/shutdown messages
     */
    fun startBlocking(serveDirectory: File, host: String, port: Int, logger: Logger) {
        if (!serveDirectory.isDirectory) {
            logger.error("Serve directory does not exist or is not a directory: ${serveDirectory.absolutePath}")
            return
        }

        val root = serveDirectory.toPath().toAbsolutePath().normalize()

        logger.lifecycle("========== Wasmline Server Deploy ==========")
        logger.lifecycle("Serving artifacts from: $root")
        logger.lifecycle("Server starting at: http://$host:$port")
        logger.lifecycle("Press Ctrl+C to stop the server.")
        logger.lifecycle("=============================================")

        embeddedServer(CIO, host = host, port = port) {
            routing {
                // Index page: list every file below the serve directory, including nested package objects.
                get("/") {
                    val files = WasmlineArtifactServing.listServedFiles(root)
                    val rootFile = root.toFile()

                    val html = buildString {
                        append("<!DOCTYPE html>")
                        append("<html><head><meta charset=\"UTF-8\">")
                        append("<title>Wasmline Artifacts</title>")
                        append("<style>")
                        append("body { font-family: monospace; margin: 2em; }")
                        append("a { color: #0066cc; }")
                        append("li { margin: 0.3em 0; }")
                        append("</style></head><body>")
                        append("<h1>Wasmline Artifacts</h1>")
                        append("<ul>")
                        for (file in files) {
                            append(WasmlineArtifactServing.renderIndexEntry(rootFile, file))
                        }
                        append("</ul>")
                        append("</body></html>")
                    }
                    call.respondText(html, ContentType.Text.Html)
                }

                // File download: serve any file below the serve directory, including
                // `artifacts/sha256/{prefix}/{digest}.{ext}` package objects.
                get("/{path...}") {
                    val segments = call.parameters.getAll("path").orEmpty()
                    val filename = segments.joinToString("/")
                    val file = WasmlineArtifactServing.resolveServedFile(root, filename)

                    if (file == null) {
                        call.respondText(
                            "File not found: ${WasmlineArtifactServing.escapeHtml(filename)}",
                            status = HttpStatusCode.NotFound,
                        )
                        return@get
                    }

                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment
                            .withParameter(ContentDisposition.Parameters.FileName, file.name)
                            .toString(),
                    )
                    call.respond(
                        LocalFileContent(
                            file = file,
                            contentType = contentTypeFor(file.extension.lowercase()),
                        ),
                    )
                }
            }
        }.start(wait = true)
    }

    private fun contentTypeFor(extension: String): ContentType = when (extension) {
        "wasm", "wlm", "cwasm", "pwasm" -> ContentType.Application.OctetStream
        "json" -> ContentType.Application.Json
        "zip" -> ContentType.Application.Zip
        else -> ContentType.Application.OctetStream
    }
}
