@file:Suppress("unused")

package crow.wasmline.engine.pulley

/**
 * Marker object for the Pulley engine variant.
 *
 * This module bundles the Pulley-only interpreter-based Wasmtime runtime as a
 * leaf dependency. It supports portable `.pwasm` artifacts only and provides
 * `libwasmtime` native libraries for all supported platforms (including
 * 32-bit Android and iOS). It does not include the Cranelift native compiler,
 * so `.cwasm` artifacts are not supported by this module.
 *
 * Usage:
 * ```kotlin
 * dependencies {
 *     implementation("crow.wasmline:wasmline:1.0.0")
 *     implementation("crow.wasmline:wasmline-engine-pulley:1.0.0")
 * }
 * ```
 *
 * @see <a href="https://github.com/crowforkotlin/wasmline">Wasmline</a>
 */
object PulleyEngine {
    const val ENGINE_NAME: String = "pulley"
}
