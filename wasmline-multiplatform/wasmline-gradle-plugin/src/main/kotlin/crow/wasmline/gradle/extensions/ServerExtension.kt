@file:Suppress("unused", "SpellCheckingInspection")

package crow.wasmline.gradle.extensions

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
 *     }
 * }
 * ```
 *
 * Date: 2026-06-05
 * Author: crowforkotlin
 */
abstract class ServerExtension @Inject constructor(objects: ObjectFactory) {

    /** The TCP port the HTTP server binds to. Default: 8080. */
    val port: Property<Int> = objects.property(Int::class.java).convention(8080)

    /** The host address the HTTP server binds to. Default: "0.0.0.0". */
    val host: Property<String> = objects.property(String::class.java).convention("0.0.0.0")
}
