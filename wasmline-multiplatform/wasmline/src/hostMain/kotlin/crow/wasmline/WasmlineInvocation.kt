/**
 * Provides result-based Wasmline invocation.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
package crow.wasmline

import crow.wasmline.internal.protocol.WasmlineResponseCodec
import crow.wasmline.invocation.WasmlineCallError
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode

fun Wasmline.callResult(action: String, payload: ByteArray = ByteArray(0)): WasmlineCallResult<ByteArray> =
    when (descriptor.invocationProtocol) {
        WasmlineInvocationProtocol.WASMLINE_CORE -> WasmlineResponseCodec.decodeLegacyCompatible(call(action, payload))

        WasmlineInvocationProtocol.WASMLINE_COMPONENT_RPC -> WasmlineComponentRpc.invoke(this, action, payload)

        WasmlineInvocationProtocol.COMPONENT_EXPORT,
        WasmlineInvocationProtocol.RAW_EXPORT,
        -> WasmlineCallResult.Failure(
            WasmlineCallError(
                code = WasmlineErrorCode.INVOCATION_PROTOCOL_MISMATCH,
                message = "Artifact protocol ${descriptor.invocationProtocol} does not expose WasmlineService actions.",
            ),
        )
    }
