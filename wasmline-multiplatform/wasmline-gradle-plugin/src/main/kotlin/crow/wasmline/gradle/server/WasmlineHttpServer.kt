@file:Suppress("SpellCheckingInspection")

package crow.wasmline.gradle.server

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
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
 * 2026/6/5
 * @author crowforkotlin
 */
internal object WasmlineHttpServer {

    /**
     * Start the HTTP server and serve files from [serveDirectory].
     *
     * Routes:
     * - `GET /` — HTML index page listing all downloadable files.
     * - `GET /{filename}` — download any file in the serve directory.
     *
     * @param serveDirectory directory containing the build artifacts
     * @param host           bind address (default "0.0.0.0")
     * @param port           TCP port (default 8080)
     * @param logger         Gradle logger for startup/shutdown messages
     */
    fun startBlocking(serveDirectory: File, host: String, port: Int, logger: Logger) {
        if (!serveDirectory.isDirectory) {
            logger.error("Serve directory does not exist or is not a directory: ${serveDirectory.absolutePath}")
            return
        }

        logger.lifecycle("========== Wasmline Server Deploy ==========")
        logger.lifecycle("Serving artifacts from: ${serveDirectory.absolutePath}")
        logger.lifecycle("Server starting at: http://$host:$port")
        logger.lifecycle("Press Ctrl+C to stop the server.")
        logger.lifecycle("=============================================")

        embeddedServer(CIO, host = host, port = port) {
            routing {
                // Index page: list all files in the serve directory.
                get("/") {
                    val files = serveDirectory.listFiles()
                        ?.filter { it.isFile }
                        ?.sortedBy { it.name }
                        ?: emptyList()

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
                            val sizeKb = file.length() / 1024.0
                            append("<li><a href=\"/${file.name}\">${file.name}</a> ($sizeKb KB)</li>")
                        }
                        append("</ul>")
                        append("</body></html>")
                    }
                    call.respondText(html, ContentType.Text.Html)
                }

                // File download: serve any file in the directory.
                get("/{filename}") {
                    val filename = call.parameters["filename"] ?: return@get
                    val file = File(serveDirectory, filename)

                    if (file.isFile && file.exists()) {
                        call.response.header(
                            HttpHeaders.ContentDisposition,
                            ContentDisposition.Attachment
                                .withParameter(ContentDisposition.Parameters.FileName, file.name)
                                .toString(),
                        )
                        val contentType = when (file.extension.lowercase()) {
                            "wasm" -> ContentType.Application.OctetStream
                            "wlm" -> ContentType.Application.OctetStream
                            "cwasm" -> ContentType.Application.OctetStream
                            "pwasm" -> ContentType.Application.OctetStream
                            "json" -> ContentType.Application.Json
                            "zip" -> ContentType.Application.Zip
                            else -> ContentType.Application.OctetStream
                        }
                        call.respondBytes(file.readBytes(), contentType)
                    } else {
                        call.respondText(
                            "File not found: $filename",
                            status = HttpStatusCode.NotFound,
                        )
                    }
                }
            }
        }.start(wait = true)
    }
}
