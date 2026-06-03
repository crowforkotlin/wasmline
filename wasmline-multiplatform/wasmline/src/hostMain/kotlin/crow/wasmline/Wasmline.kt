@file:Suppress("unused", "SpellCheckingInspection")

package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineHostDispatcher

/**
 * Host-side runtime handle for a loaded module.
 *
 * This class is the bridge between the host application and a loaded Wasm plugin.
 * Instances are obtained through `WasmlineLoader.load()`, not created directly.
 *
 * Engine lifecycle and loading are managed entirely by `WasmlineLoader`.
 */
expect class Wasmline internal constructor(moduleKey: String, config: WasmlineConfig) {

    val config: WasmlineConfig

    internal fun setOutbound(dispatcher: WasmlineHostDispatcher)
    internal fun call(action: String, inputBytes: ByteArray): ByteArray
    fun close()
}

/**
 * Host-side engine lifecycle functions for Wasmline runtime management.
 *
 * These are low-level runtime operations used primarily by `WasmlineLoader`.
 * Application code should prefer using `WasmlineLoader` instead of calling these directly.
 */
expect fun wasmlineBootstrap()
expect fun wasmlineShutdown()
expect fun wasmlineWarmup(mode: WasmlineWarmupMode)
expect fun wasmlineLoadArtifact(filepath: String, config: WasmlineConfig): WasmlineLoadState
