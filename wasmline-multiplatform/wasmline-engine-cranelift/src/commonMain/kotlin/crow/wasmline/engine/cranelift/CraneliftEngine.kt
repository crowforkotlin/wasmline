@file:Suppress("unused", "SpellCheckingInspection")

package crow.wasmline.engine.cranelift

/**
 * Marker object for the Cranelift engine variant.
 *
 * This module bundles the Wasmtime runtime built with the Cranelift compiler
 * and Pulley support. It supports platform-specific `.cwasm` AOT artifacts and
 * portable `.pwasm` artifacts; the loader prefers `.cwasm` and falls back to
 * matching-bitness `.pwasm` when a matching native artifact is unavailable. It provides
 * `libwasmtime` native libraries for 64-bit platforms only (Android arm64-v8a /
 * x86_64, JVM desktop, macOS, Windows).
 *
 * Note: 32-bit Android (armeabi-v7a, x86) and iOS do not ship a Cranelift
 * native runtime. Use `wasmline-engine-pulley` and `.pwasm` for those targets.
 *
 * Usage:
 * ```kotlin
 * dependencies {
 *     implementation("crow.wasmline:wasmline:1.0.0")
 *     implementation("crow.wasmline:wasmline-engine-cranelift:1.0.0")
 * }
 * ```
 *
 * @see <a href="https://github.com/crowforkotlin/wasmline">Wasmline</a>
 */
object CraneliftEngine {
    const val ENGINE_NAME: String = "cranelift"
}
