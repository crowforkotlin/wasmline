@file:Suppress("FunctionName", "unused", "OPT_IN_USAGE")

package crow.wasmline

import crow.wasmline.internal.protocol.WasmlineResponseCodec
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineFailure

@PublishedApi
internal fun wasmlineHandleInbound(actionLen: Int, inputLen: Int) {
    val action = if (actionLen > 0) WasmlineWasmBridge.readBytesFromHost(0, actionLen).decodeToString() else null
    val args = if (inputLen > 0) WasmlineWasmBridge.readBytesFromHost(1, inputLen) else null
    val result = try {
        WasmlineRouter.dispatch(action, args)
    } catch (error: Throwable) {
        WasmlineCallResult.Failure(
            WasmlineFailure(
                code = WasmlineErrorCode.HANDLER_FAILED,
                message = error.message ?: "Wasmline action handler failed.",
            ),
        )
    }
    val response = when (result) {
        is WasmlineCallResult.Success -> WasmlineResponseCodec.encodeSuccess(result.value)
        is WasmlineCallResult.Failure -> WasmlineResponseCodec.encodeFailure(result.failure)
    }
    WasmlineWasmBridge.sendResult(response)
}
