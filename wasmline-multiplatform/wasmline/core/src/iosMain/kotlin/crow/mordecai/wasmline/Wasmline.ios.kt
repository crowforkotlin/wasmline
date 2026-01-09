package crow.mordecai.wasmline

actual class Wasmline actual constructor(moduleKey: String) {
    actual suspend fun setOutbound(dispatcher: WasmlineHostDispatcher) {
    }

    actual suspend fun call(action: String, inputBytes: ByteArray): ByteArray {
        return byteArrayOf()
    }

    actual fun release() {
    }

    actual companion object {
        actual suspend fun load(
            filepath: String,
            cacheFilepath: String?,
            threadSafe: Boolean
        ): WasmlineLoadState {
            return WasmlineLoadState.Failure(WasmlineLoadState.CODE_FAILURE, "")
        }

        actual fun init() {
        }

        actual fun release() {
        }
    }
}