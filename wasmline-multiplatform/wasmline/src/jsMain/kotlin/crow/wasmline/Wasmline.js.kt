package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineHostDispatcher

actual class Wasmline internal actual constructor(moduleKey: String, actual val config: WasmlineConfig) {
    private val delegate = BrowserWasmline(moduleKey)

    internal actual fun setOutbound(dispatcher: WasmlineHostDispatcher) {
        delegate.setOutbound { action, payloadBase64 ->
            dispatcher.dispatch(action, payloadBase64.decodeBase64Payload()).encodeBase64Payload()
        }
    }

    internal actual fun call(action: String, inputBytes: ByteArray): ByteArray =
        delegate.call(action, inputBytes.encodeBase64Payload()).decodeBase64Payload()

    actual fun close() {
        delegate.close()
    }
}

actual fun wasmlineBootstrap() = browserWasmlineBootstrap()
actual fun wasmlineShutdown() = browserWasmlineShutdown()
actual fun wasmlineWarmup(mode: WasmlineWarmupMode) = browserWasmlineWarmup(mode)
actual fun wasmlineLoadArtifact(filepath: String, config: WasmlineConfig): WasmlineLoadState = browserWasmlineLoadArtifact(filepath, config)
