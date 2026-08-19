package crow.wasmline.extensions

/**
 * Confirms that the statically linked Kotlin/Native bridge is available.
 *
 * Native executables link the bridge through the selected engine KLIB, so no
 * dynamic library loading is required here.
 *
 * Author: crowforkotlin
 * Date: 2026-08-19
 */
internal actual fun ensureNativeRuntimeLoaded() = Unit
