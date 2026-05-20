package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineHostDispatcher

actual class Wasmline internal actual constructor(moduleKey: String) {
    private val delegate = BrowserWasmline(moduleKey)

    actual companion object {
        actual fun load(filepath: String, threadSafe: Boolean): WasmlineLoadState {
            return BrowserWasmlineRuntime.load(filepath, threadSafe, ::Wasmline)
        }

        actual fun init() = BrowserWasmlineRuntime.init()

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
