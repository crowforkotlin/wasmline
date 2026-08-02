@file:Suppress("unused", "SpellCheckingInspection")

package crow.wasmline.gradle.extensions

import org.gradle.api.Project
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Top-level DSL extension registered under the `wasmline` block.
 *
 * ```kotlin
 * wasmline {
 *     manifest {
 *         pluginId = "crow.wasmline.demo"
 *         version = "1.0.0"
 *         signingKey = file("../keys/private.key").readText()
 *     }
 *     wasmtime {
 *         // Configure wasmtime location and behavior
 *         directory = file(System.getenv("WASMTIME_MIN_HOME") ?: "$home/.wasmline/wasmtime")
 *
 *         // Enable auto-download if wasmtime is missing
 *         autoDownload = true
 *         version = "latest" // or specific version like "v47.0.2"
 *
 *         // Optional: Set GitHub token for higher API rate limits
 *         // Use environment variable from CI or local config
 *         githubToken.set(System.getenv("GITHUB_TOKEN"))
 *     }
 *     server {
 *         port = 8080
 *     }
 *
 *     // Optionally choose which assemble variant the server deploy task depends on.
 *     // "debug" (default) -> dependsOn wasmlineAssembleDebug
 *     // "release"        -> dependsOn wasmlineAssembleRelease
 *     serverDeployVariant = "debug"
 * }
 * ```
 *
 * 2026/6/5
 * @author crowforkotlin
 */
open class WasmlineExtension @Inject constructor(project: Project) {

    private val objects = project.objects

    /** Manifest metadata configuration. */
    val manifest: ManifestExtension = objects.newInstance(ManifestExtension::class.java)

    /** Wasmtime AOT compiler configuration. */
    val wasmtime: WasmtimeExtension = objects.newInstance(WasmtimeExtension::class.java)

    /** HTTP server configuration for the deployment task. */
    val server: ServerExtension = objects.newInstance(ServerExtension::class.java)

    /**
     * Which assemble variant the `wasmlineServerDeploy` task should depend on.
     * Accepts "debug" or "release". Default: "debug".
     */
    val serverDeployVariant: Property<String> = objects.property(String::class.java).convention("debug")

    /** Configure the [manifest] block. */
    fun manifest(action: ManifestExtension.() -> Unit) {
        manifest.action()
    }

    /** Configure the [wasmtime] block. */
    fun wasmtime(action: WasmtimeExtension.() -> Unit) {
        wasmtime.action()
    }

    /** Configure the [server] block. */
    fun server(action: ServerExtension.() -> Unit) {
        server.action()
    }
}
