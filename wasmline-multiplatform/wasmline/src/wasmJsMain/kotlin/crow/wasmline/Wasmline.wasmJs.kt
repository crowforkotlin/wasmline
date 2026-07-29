package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineHostDispatcher

actual class Wasmline internal actual constructor(moduleKey: String, actual val config: WasmlineConfig) {
    private val delegate = BrowserWasmline(moduleKey)

    internal actual fun setOutbound(dispatcher: WasmlineHostDispatcher) {
        delegate.setOutbound(dispatcher)
    }

    internal actual fun call(action: String, inputBytes: ByteArray): ByteArray = delegate.call(action, inputBytes)

    actual fun close() {
        delegate.close()
    }
}

actual fun wasmlineBootstrap() = browserWasmlineBootstrap()
actual fun wasmlineShutdown() = browserWasmlineShutdown()
actual fun wasmlineWarmup(mode: WasmlineWarmupMode) = browserWasmlineWarmup(mode)
actual fun wasmlineLoadArtifact(filepath: String, config: WasmlineConfig): WasmlineLoadState = browserWasmlineLoadArtifact(filepath, config)
