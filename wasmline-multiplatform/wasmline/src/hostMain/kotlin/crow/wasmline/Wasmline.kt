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
 * Runtime bridge functions used exclusively by WasmlineLoader (friend module).
 * These are internal and not visible to external consumers.
 */
internal expect fun wasmlineBootstrap()
internal expect fun wasmlineShutdown()
internal expect fun wasmlineWarmup(mode: WasmlineWarmupMode)
internal expect fun wasmlineLoadArtifact(filepath: String, config: WasmlineConfig): WasmlineLoadState
