@file:Suppress("unused", "SpellCheckingInspection")

package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineHostDispatcher
import crow.wasmline.internal.protocol.WasmlineResponseCodec
import crow.wasmline.invocation.WasmlineCallResult

/**
 * Host-side runtime handle for a loaded module.
 *
 * This class is the bridge between the host application and a loaded Wasm plugin.
 * Instances are obtained through `WasmlineLoader.load()`, not created directly.
 *
 * Engine lifecycle and loading are managed entirely by `WasmlineLoader`.
 */
expect class Wasmline internal constructor(moduleKey: String, config: WasmlineConfig, descriptor: WasmlineArtifactDescriptor) {

    val config: WasmlineConfig
    val descriptor: WasmlineArtifactDescriptor

    internal fun setOutbound(dispatcher: WasmlineHostDispatcher)
    internal fun setComponentHostDispatcher(dispatcher: WasmlineComponentHostDispatcher)
    internal fun call(action: String, inputBytes: ByteArray): ByteArray
    internal fun invokeRawCarrier(exportName: String, arguments: ByteArray): WasmlineCallResult<ByteArray>
    internal fun invokeComponentCarrier(exportName: String, arguments: ByteArray): WasmlineCallResult<ByteArray>
    fun close()
}

/** Binds an immutable typed host registry to one loaded Component handle. */
fun Wasmline.bindComponentHost(registry: WasmlineComponentHostRegistry): Wasmline {
    require(descriptor.executionModel == WasmlineExecutionModel.COMPONENT_MODEL) {
        "Typed Component host registries can only bind to COMPONENT_MODEL artifacts."
    }
    setComponentHostDispatcher(WasmlineComponentHostDispatcher(registry))
    return this
}

/**
 * Binds the Wasmline Component RPC envelope used by Components that import
 * `wasmline:rpc/host`. The handler is invoked when the Component calls back
 * into the host and returns the raw RPC payload or a structured failure.
 */
fun Wasmline.bindComponentRpc(handler: (action: String, payload: ByteArray) -> WasmlineCallResult<ByteArray>): Wasmline {
    require(descriptor.executionModel == WasmlineExecutionModel.COMPONENT_MODEL) {
        "Component RPC handlers can only bind to COMPONENT_MODEL artifacts."
    }
    setOutbound(
        WasmlineHostDispatcher { action, payload ->
            when (val result = handler(action, payload)) {
                is WasmlineCallResult.Success -> WasmlineResponseCodec.encodeSuccess(result.value)
                is WasmlineCallResult.Failure -> WasmlineResponseCodec.encodeFailure(result.error)
            }
        },
    )
    return this
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

/** Returns immutable native runtime identity, or `null` on browser runtimes. */
expect fun wasmlineNativeRuntimeInfo(): WasmlineNativeRuntimeInfo?

/** Returns the immutable native engine variant, or `null` on browser runtimes. */
fun wasmlineNativeBackend(): WasmlineNativeBackend? = wasmlineNativeRuntimeInfo()?.backend
internal expect fun wasmlineRuntimeCapabilities(): WasmlineRuntimeCapabilities
expect fun wasmlineLoadArtifact(filepath: String, config: WasmlineConfig): WasmlineLoadState
expect fun wasmlineLoadArtifact(descriptor: WasmlineArtifactDescriptor, config: WasmlineConfig): WasmlineLoadState
