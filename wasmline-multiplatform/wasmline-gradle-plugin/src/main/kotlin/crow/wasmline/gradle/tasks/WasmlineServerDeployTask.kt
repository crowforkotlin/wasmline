@file:Suppress("SpellCheckingInspection")

package crow.wasmline.gradle.tasks

import crow.wasmline.gradle.server.WasmlineHttpServer
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Gradle task that starts an HTTP server to serve wasmline build artifacts.
 *
 * The server runs in foreground blocking mode — the Gradle process will
 * block until the user interrupts it (Ctrl+C). This is intended for local
 * development and testing, allowing clients to load `.wlm` plugins over the
 * network.
 *
 * Usage:
 * ```bash
 * ./gradlew :sample-plugin:wasmlineServerDeploy
 * ```
 *
 * The server will listen on the configured port (default 8080) and serve
 * all files in the assembly output directory, including `manifest.wlm`.
 *
 * Date: 2026-06-05
 * Author: crowforkotlin
 */
abstract class WasmlineServerDeployTask : DefaultTask() {

    init {
        group = "wasmline"
        description = "Deploy and start an HTTP server to serve wasmline artifacts"
    }

    /** Directory containing the assembled wasmline artifacts (manifest.wlm, .wasm, etc.). */
    @get:InputDirectory
    abstract val serveDirectory: DirectoryProperty

    /** TCP port the HTTP server binds to. Default: 8080. */
    @get:Input
    abstract val port: Property<Int>

    /** Host address the HTTP server binds to. Default: "0.0.0.0". */
    @get:Input
    abstract val host: Property<String>

    @TaskAction
    fun deploy() {
        WasmlineHttpServer.startBlocking(
            serveDirectory = serveDirectory.get().asFile,
            host = host.get(),
            port = port.get(),
            logger = logger,
        )
    }
}
