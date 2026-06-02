package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineHostDispatcher

actual class Wasmline internal actual constructor(
    moduleKey: String,
    actual val config: WasmlineConfig,
) {
    private val delegate = BrowserWasmline(moduleKey)

    actual companion object {
        actual fun load(
            filepath: String,
            config: WasmlineConfig,
        ): WasmlineLoadState {
            return BrowserWasmlineRuntime.load(filepath, config, ::Wasmline)
        }

        actual fun bootstrap() = BrowserWasmlineRuntime.bootstrap()

        actual fun warmup(mode: WasmlineWarmupMode) = Unit

        actual fun shutdown() = BrowserWasmlineRuntime.shutdown()
    }

    actual internal fun setOutbound(dispatcher: WasmlineHostDispatcher) {
        delegate.setOutbound { action, payloadBase64 ->
            dispatcher.dispatch(action, payloadBase64.decodeBase64Payload()).encodeBase64Payload()
        }
    }

    actual internal fun call(action: String, inputBytes: ByteArray): ByteArray {
        return delegate.call(action, inputBytes.encodeBase64Payload()).decodeBase64Payload()
    }

    actual fun close() {
        delegate.close()
    }
}
