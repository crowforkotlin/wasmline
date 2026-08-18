@file:Suppress("unused", "SpellCheckingInspection")

package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineHostDispatcher
import crow.wasmline.invocation.WasmlineCallResult

/**
 * Host-side runtime handle for a loaded module.
 *
 * This class is the bridge between the host application and a loaded Wasm plugin.
 * Instances are obtained through `WasmlineLoader.load()`, not created directly.
 *
 * Artifact loading is owned by `WasmlineLoader`; process-wide lifecycle is
 * owned by [WasmlineRuntime].
 */
expect class Wasmline internal constructor(moduleKey: String, config: WasmlineConfig, descriptor: WasmlineArtifactDescriptor) {

    val config: WasmlineConfig
    val descriptor: WasmlineArtifactDescriptor
    internal val hostServiceRegistry: WasmlineHostServiceRegistry
    internal val componentModuleState: WasmlineComponentModuleState

    /** Binds an immutable typed host registry to this loaded Component handle. */
    fun bindComponentHost(registry: WasmlineComponentHostRegistry): Wasmline

    /**
     * Binds the Wasmline Service envelope used by Components that import
     * `wasmline:service/host` to this loaded Component handle.
     */
    fun bindComponentService(handler: (action: String, payload: ByteArray) -> WasmlineCallResult<ByteArray>): Wasmline

    internal fun setOutbound(dispatcher: WasmlineHostDispatcher)
    internal fun setComponentHostDispatcher(dispatcher: WasmlineComponentHostDispatcher)
    internal fun call(action: String, inputBytes: ByteArray): ByteArray
    internal fun invokeRawCarrier(exportName: String, arguments: ByteArray): WasmlineCallResult<ByteArray>
    internal fun invokeComponentCarrier(exportName: String, arguments: ByteArray): WasmlineCallResult<ByteArray>
    internal fun instantiateComponentInstance(instanceKey: String, dispatcher: WasmlineComponentHostDispatcher): Boolean
    internal fun invokeComponentInstanceCarrier(
        instanceKey: String,
        exportName: String,
        arguments: ByteArray,
    ): WasmlineCallResult<ByteArray>
    internal fun releaseComponentInstance(instanceKey: String)
    internal fun dropComponentResource(instanceKey: String, reference: WasmlineComponentValue.ResourceValue): Boolean
    internal fun createComponentHostResource(
        instanceKey: String,
        interfaceId: String,
        resourceName: String,
        representation: UInt,
    ): WasmlineCallResult<WasmlineComponentValue.ResourceValue>
    fun close()
}
