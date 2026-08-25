package crow.wasmline

import crow.wasmline.internal.protocol.WasmlineResponseCodec
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineFailure

/** Provides result-based Wasmline invocation. */
fun Wasmline.callResult(action: String, payload: ByteArray = ByteArray(0)): WasmlineCallResult<ByteArray> =
    if (descriptor.invocationProtocol != WasmlineInvocationProtocol.WASMLINE_SERVICE) {
        WasmlineCallResult.Failure(
            WasmlineFailure(
                code = WasmlineErrorCode.INVOCATION_PROTOCOL_MISMATCH,
                message = "Artifact protocol ${descriptor.invocationProtocol} does not expose WasmlineService actions.",
            ),
        )
    } else {
        when (descriptor.executionModel) {
            WasmlineExecutionModel.CORE_WASM -> WasmlineResponseCodec.decodeLegacyCompatible(call(action, payload))
            WasmlineExecutionModel.COMPONENT_MODEL -> WasmlineComponentService.invoke(this, action, payload)
        }
    }
