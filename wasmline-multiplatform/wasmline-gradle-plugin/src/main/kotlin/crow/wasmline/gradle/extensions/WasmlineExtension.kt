@file:Suppress("unused", "SpellCheckingInspection")

package crow.wasmline.gradle.extensions

import org.gradle.api.Project
import javax.inject.Inject

/**
 * Top-level DSL extension registered under the `wasmline` block.
 *
 * ```kotlin
 * wasmline {
 *     manifest {
 *         pluginId = "crow.wasmline.demo"
 *         version = "1.0.0"
 *         signingKey = file("../keys/private.key")
 *     }
 *     wasmtime {
 *         // Configure wasmtime location and behavior
 *         directory = file(System.getenv("WASMTIME_MIN_HOME") ?: "$home/.wasmline/wasmtime")
 *
 *         // Enable auto-download if wasmtime is missing
 *         autoDownload = true
 *         version = "latest" // or specific version like "v48.0.1"
 *
 *         // Optional: Set GitHub token for higher API rate limits
 *         // Use environment variable from CI or local config
 *         githubToken.set(System.getenv("GITHUB_TOKEN"))
 *     }
 *     server {
 *         port = 8080
 *         deployVariant = WasmlineBuildVariant.RELEASE
 *     }
 * }
 * ```
 *
 * Date: 2026-06-05
 * Author: crowforkotlin
 */
public open class WasmlineExtension @Inject constructor(project: Project) {

    private val objects = project.objects

    /** Manifest metadata configuration. */
    public val manifest: ManifestExtension = objects.newInstance(ManifestExtension::class.java)

    /** Wasmtime AOT compiler configuration. */
    public val wasmtime: WasmtimeExtension = objects.newInstance(WasmtimeExtension::class.java)

    /** WIT and Component Model build configuration. */
    public val component: ComponentExtension = objects.newInstance(ComponentExtension::class.java, project)

    /** HTTP server configuration for the deployment task. */
    public val server: ServerExtension = objects.newInstance(ServerExtension::class.java)

    /** Configure the [manifest] block. */
    public fun manifest(action: ManifestExtension.() -> Unit) {
        manifest.action()
    }

    /** Configure the [wasmtime] block. */
    public fun wasmtime(action: WasmtimeExtension.() -> Unit) {
        wasmtime.action()
    }

    /** Configure the [component] block. */
    public fun component(action: ComponentExtension.() -> Unit) {
        component.action()
    }

    /** Configure the [server] block. */
    public fun server(action: ServerExtension.() -> Unit) {
        server.action()
    }
}
