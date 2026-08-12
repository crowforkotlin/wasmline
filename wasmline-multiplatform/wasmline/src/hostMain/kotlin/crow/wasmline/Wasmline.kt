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
 * Engine lifecycle and loading are managed entirely by `WasmlineLoader`.
 */
expect class Wasmline internal constructor(moduleKey: String, config: WasmlineConfig, descriptor: WasmlineArtifactDescriptor) {

    val config: WasmlineConfig
    val descriptor: WasmlineArtifactDescriptor
    internal val hostServiceRegistry: WasmlineHostServiceRegistry
    internal val componentModuleState: WasmlineComponentModuleState

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

/** Binds an immutable typed host registry to one loaded Component handle. */
fun Wasmline.bindComponentHost(registry: WasmlineComponentHostRegistry): Wasmline {
    require(descriptor.invocationProtocol == WasmlineInvocationProtocol.COMPONENT_EXPORT) {
        "Typed Component host registries require invocationProtocol=COMPONENT_EXPORT."
    }
    setComponentHostDispatcher(WasmlineComponentHostDispatcher(registry))
    return this
}

/**
 * Binds the Wasmline Service envelope used by Components that import
 * `wasmline:service/host`. The handler is invoked when the Component calls back
 * into the host and returns the raw service payload or a structured failure.
 */
fun Wasmline.bindComponentService(handler: (action: String, payload: ByteArray) -> WasmlineCallResult<ByteArray>): Wasmline {
    require(
        descriptor.executionModel == WasmlineExecutionModel.COMPONENT_MODEL &&
            descriptor.invocationProtocol == WasmlineInvocationProtocol.WASMLINE_SERVICE,
    ) {
        "Component Service handlers require COMPONENT_MODEL with invocationProtocol=WASMLINE_SERVICE."
    }
    if (hostServiceRegistry.registerRaw(handler)) setOutbound(hostServiceRegistry.dispatcher)
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
