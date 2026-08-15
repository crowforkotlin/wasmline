@file:Suppress("unused", "SpellCheckingInspection")

package crow.wasmline.gradle.extensions

import crow.wasmline.gradle.WasmlineBuildVariant
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * DSL extension for configuring the wasmline HTTP server used by the
 * `wasmlineServerDeploy` task.
 *
 * ```kotlin
 * wasmline {
 *     server {
 *         port = 8080
 *         host = "0.0.0.0"
 *         deployVariant = WasmlineBuildVariant.RELEASE
 *     }
 * }
 * ```
 *
 * Date: 2026-06-05
 * Author: crowforkotlin
 */
public abstract class ServerExtension @Inject constructor(objects: ObjectFactory) {

    /** The TCP port the HTTP server binds to. Default: 8080. */
    public val port: Property<Int> = objects.property(Int::class.java).convention(8080)

    /** The host address the HTTP server binds to. Default: "0.0.0.0". */
    public val host: Property<String> = objects.property(String::class.java).convention("0.0.0.0")

    /** Package variant built before the server starts. Default: [WasmlineBuildVariant.DEBUG]. */
    public val deployVariant: Property<WasmlineBuildVariant> =
        objects.property(WasmlineBuildVariant::class.java).convention(WasmlineBuildVariant.DEBUG)
}
