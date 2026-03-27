@file:Suppress("unused")

package crow.wasmline.internal.bridge

fun interface WasmlineHostDispatcher {
    fun dispatch(action: String, payload: ByteArray): ByteArray
}

