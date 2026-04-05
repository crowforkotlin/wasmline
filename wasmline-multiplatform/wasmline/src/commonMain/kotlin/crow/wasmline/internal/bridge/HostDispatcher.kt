@file:Suppress("unused")

package crow.wasmline.internal.bridge

internal fun interface WasmlineHostDispatcher {
    fun dispatch(action: String, payload: ByteArray): ByteArray
}

