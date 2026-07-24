@file:Suppress("FunctionName", "unused", "OPT_IN_USAGE")

package crow.wasmline

import crow.wasmline.model.WasmError
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

@PublishedApi
internal fun wasmlineHandleInbound(actionLen: Int, inputLen: Int) {
    val action = if (actionLen > 0) WasmlineWasmBridge.readBytesFromHost(0, actionLen).decodeToString() else null
    val args = if (inputLen > 0) WasmlineWasmBridge.readBytesFromHost(1, inputLen) else null
    println("[WasmKotlin] Receive action: $action, size: $inputLen")
    val result: ByteArray = try {
        WasmlineRouter.dispatch(action, args) ?: return
    } catch (e: Throwable) {
        println("[WasmKotlin] Failed action: $action, message: ${e.message}")
        ProtoBuf.encodeToByteArray(
            value = WasmError(message = e.message ?: "Unknown Wasmline wasm error"),
        )
    }
    WasmlineWasmBridge.sendResult(result)
}
