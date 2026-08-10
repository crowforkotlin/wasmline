package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineHostDispatcher
import crow.wasmline.invocation.WasmlineCallResult

actual class Wasmline internal actual constructor(
    moduleKey: String,
    actual val config: WasmlineConfig,
    actual val descriptor: WasmlineArtifactDescriptor,
) {
    private val delegate = BrowserWasmline(moduleKey)

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

    actual fun close() {
        delegate.close()
    }
}

actual fun wasmlineBootstrap() = browserWasmlineBootstrap()
actual fun wasmlineShutdown() = browserWasmlineShutdown()
actual fun wasmlineWarmup(mode: WasmlineWarmupMode) = browserWasmlineWarmup(mode)
actual fun wasmlineNativeRuntimeInfo(): WasmlineNativeRuntimeInfo? = null
internal actual fun wasmlineRuntimeCapabilities(): WasmlineRuntimeCapabilities = browserRuntimeCapabilities()
actual fun wasmlineLoadArtifact(filepath: String, config: WasmlineConfig): WasmlineLoadState = browserWasmlineLoadArtifact(filepath, config)
actual fun wasmlineLoadArtifact(descriptor: WasmlineArtifactDescriptor, config: WasmlineConfig): WasmlineLoadState =
    browserWasmlineLoadArtifact(descriptor, config)

private fun browserRuntimeCapabilities(): WasmlineRuntimeCapabilities = WasmlineRuntimeCapabilities(
    wasmtimeVersion = "0.0.0",
    supportsCranelift = false,
    supportsPulley = false,
    targetOs = "browser",
    targetCpu = "wasmjs",
    is64Bit = false,
)
