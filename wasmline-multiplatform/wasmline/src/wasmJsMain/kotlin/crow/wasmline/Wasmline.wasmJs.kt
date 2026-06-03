package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineHostDispatcher

actual class Wasmline internal actual constructor(
    moduleKey: String,
    actual val config: WasmlineConfig,
) {
    private val delegate = BrowserWasmline(moduleKey)

    internal actual fun setOutbound(dispatcher: WasmlineHostDispatcher) {
        delegate.setOutbound { action, payloadBase64 ->
            dispatcher.dispatch(action, payloadBase64.decodeBase64Payload()).encodeBase64Payload()
        }
    }

    internal actual fun call(action: String, inputBytes: ByteArray): ByteArray {
        return delegate.call(action, inputBytes.encodeBase64Payload()).decodeBase64Payload()
    }

    actual fun close() {
        delegate.close()
    }
}

internal actual fun wasmlineBootstrap() = browserWasmlineBootstrap()
internal actual fun wasmlineShutdown() = browserWasmlineShutdown()
internal actual fun wasmlineWarmup(mode: WasmlineWarmupMode) = browserWasmlineWarmup(mode)
internal actual fun wasmlineLoadArtifact(filepath: String, config: WasmlineConfig): WasmlineLoadState =
    browserWasmlineLoadArtifact(filepath, config)
