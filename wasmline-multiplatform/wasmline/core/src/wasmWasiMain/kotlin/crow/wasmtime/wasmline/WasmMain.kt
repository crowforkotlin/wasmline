@file:Suppress("FunctionName", "unused", "OPT_IN_USAGE")

package crow.wasmtime.wasmline

import crow.mordecai.wasmline.extensions.info
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

fun WasmlineInitialize(actionLen: Int, inputLen: Int) {

    val action = if (actionLen > 0) WasmBridge.readBytesFromHost(0, actionLen).decodeToString() else null
    val args = if (inputLen > 0) WasmBridge.readBytesFromHost(1, inputLen) else null

     println("[WasmKotlin] Receive action: $action, size: $inputLen")

    val result: ByteArray = try { WasmRouter.dispatch(action, args) ?: return } catch (e: Throwable) { ProtoBuf.encodeToByteArray(value = Error(message = (e.message ?: return))) }
    WasmBridge.sendResult(result)
}

fun main() { println("[Wasm SDK] Initialized.") }