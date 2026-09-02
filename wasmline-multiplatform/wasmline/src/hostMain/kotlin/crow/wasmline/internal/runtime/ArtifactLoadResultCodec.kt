package crow.wasmline.internal.runtime

import crow.wasmline.internal.protocol.WasmlineResponseCodec
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineFailure

/** Decodes the empty-success WLMF carrier returned by native artifact loading. */
internal fun decodeArtifactLoadResult(bytes: ByteArray?): WasmlineCallResult<Unit> {
    if (bytes == null) {
        return WasmlineCallResult.Failure(
            WasmlineFailure(WasmlineErrorCode.TRANSPORT_FAILURE, "Native artifact load returned no response."),
        )
    }
    return when (val decoded = WasmlineResponseCodec.decode(bytes)) {
        is WasmlineCallResult.Failure -> decoded

        is WasmlineCallResult.Success -> if (decoded.value.isEmpty()) {
            WasmlineCallResult.Success(Unit)
        } else {
            WasmlineCallResult.Failure(
                WasmlineFailure(
                    WasmlineErrorCode.RESPONSE_MALFORMED,
                    "Native artifact load returned an unexpected success payload.",
                ),
            )
        }
    }
}
