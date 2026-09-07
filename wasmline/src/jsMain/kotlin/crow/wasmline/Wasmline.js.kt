package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineHostDispatcher
import crow.wasmline.internal.component.WasmlineComponentBindings
import crow.wasmline.internal.component.WasmlineComponentHostDispatcher
import crow.wasmline.internal.component.WasmlineComponentModuleState
import crow.wasmline.internal.core.CoreWasmBackendModule
import crow.wasmline.internal.service.WasmlineHostServiceRegistry
import crow.wasmline.invocation.WasmlineCallResult

actual class Wasmline internal actual constructor(
    moduleKey: String,
    actual val config: WasmlineConfig,
    actual val descriptor: WasmlineArtifactDescriptor,
) {
    internal actual val hostServiceRegistry: WasmlineHostServiceRegistry = WasmlineHostServiceRegistry()
    internal actual val componentModuleState: WasmlineComponentModuleState = WasmlineComponentModuleState(this)
    private val delegate = BrowserWasmline(moduleKey)

    actual fun bindComponentHost(registry: WasmlineComponentHostRegistry): Wasmline = WasmlineComponentBindings.bindHost(this, registry)

    actual fun bindComponentService(handler: (action: String, payload: ByteArray) -> WasmlineCallResult<ByteArray>): Wasmline =
        WasmlineComponentBindings.bindService(this, handler)

    internal actual fun setOutbound(dispatcher: WasmlineHostDispatcher) {
        delegate.setOutbound(dispatcher)
    }

    internal actual fun setComponentHostDispatcher(dispatcher: WasmlineComponentHostDispatcher): Unit =
        throw UnsupportedOperationException("Browser host does not support typed Component host imports.")

    internal actual fun call(action: String, inputBytes: ByteArray): ByteArray = delegate.call(action, inputBytes)

    internal actual fun invokeRawCarrier(exportName: String, arguments: ByteArray): WasmlineCallResult<ByteArray> =
        delegate.invokeRawCarrier(exportName, arguments)

    internal actual fun invokeComponentCarrier(exportName: String, arguments: ByteArray): WasmlineCallResult<ByteArray> =
        delegate.invokeComponentCarrier(exportName, arguments)

    internal actual fun instantiateComponentInstance(instanceKey: String, dispatcher: WasmlineComponentHostDispatcher): Boolean =
        throw UnsupportedOperationException("Browser host does not support Component Model instances.")

    internal actual fun invokeComponentInstanceCarrier(
        instanceKey: String,
        exportName: String,
        arguments: ByteArray,
    ): WasmlineCallResult<ByteArray> = throw UnsupportedOperationException(
        "Browser host does not support Component Model instances.",
    )

    internal actual fun releaseComponentInstance(instanceKey: String) = Unit

    internal actual fun dropComponentResource(instanceKey: String, reference: WasmlineComponentValue.ResourceValue): Boolean = false

    internal actual fun createComponentHostResource(
        instanceKey: String,
        interfaceId: String,
        resourceName: String,
        representation: UInt,
    ): WasmlineCallResult<WasmlineComponentValue.ResourceValue> = throw UnsupportedOperationException(
        "Browser host does not support Component Model resources.",
    )

    internal actual fun createCoreWasmBackend(): WasmlineCallResult<CoreWasmBackendModule> = delegate.createCoreWasmBackend()

    actual fun asCoreWasmModule(): WasmlineCallResult<CoreWasmModule> = createCoreWasmModule(this)

    actual fun close() {
        componentModuleState.close()
        hostServiceRegistry.clear()
        delegate.close()
    }
}

internal actual fun platformWasmlinePreload() = browserWasmlinePreload()
internal actual fun platformWasmlineShutdown() = browserWasmlineShutdown()
internal actual fun platformWasmlineWarmUp(engine: WasmlineEngineKind): Unit = browserWasmlineWarmUp(engine)
internal actual fun platformWasmlineNativeRuntimeInfo(): WasmlineNativeRuntimeInfo? = null
internal actual fun platformWasmlineRuntimeCapabilities(): WasmlineRuntimeCapabilities = browserRuntimeCapabilities()
internal actual fun platformWasmlineLoadArtifact(filepath: String, config: WasmlineConfig): WasmlineLoadState =
    browserWasmlineLoadArtifact(filepath, config)
internal actual fun platformWasmlineLoadArtifact(descriptor: WasmlineArtifactDescriptor, config: WasmlineConfig): WasmlineLoadState =
    browserWasmlineLoadArtifact(descriptor, config)

private fun browserRuntimeCapabilities(): WasmlineRuntimeCapabilities = WasmlineRuntimeCapabilities(
    backend = null,
    supportedArtifactFormats = setOf(WasmlineArtifactFormat.RAW_WASM),
    wasmtimeVersion = "0.0.0",
    aotCompatibilityProfileIdsByBackend = emptyMap(),
    nativeBridgeAbiVersion = 0,
    wasmlineReleaseVersion = WasmlineReleaseIdentity.RELEASE_VERSION,
    operatingSystem = "browser",
    architecture = "wasm32",
    pointerWidth = 32,
    supportedCpuFeatureProfiles = emptySet(),
)
