package crow.wasmline

import crow.wasmline.internal.invocation.WasmlineTypedInvocationCodec
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineFailure

/**
 * Holds values returned by a direct raw export invocation.
 *
 * Date: 2026-09-02
 * Author: crowforkotlin
 */
data class WasmlineRawCallResult(val values: List<RawValue>)

/**
 * Holds values returned by a direct Component export invocation.
 *
 * Date: 2026-09-02
 * Author: crowforkotlin
 */
data class WasmlineComponentCallResult(val values: List<WasmlineComponentValue>)

/** Invokes a typed raw export on this loaded host runtime. */
fun Wasmline.invokeRawResult(exportName: String, arguments: List<RawValue> = emptyList()): WasmlineCallResult<WasmlineRawCallResult> {
    val protocolError = validateProtocol(WasmlineInvocationProtocol.RAW_EXPORT, exportName)
    if (protocolError != null) return protocolError

    return when (val encoded = WasmlineTypedInvocationCodec.encodeRawArguments(arguments)) {
        is WasmlineCallResult.Failure -> encoded

        is WasmlineCallResult.Success -> when (val carrier = invokeRawCarrier(exportName, encoded.value)) {
            is WasmlineCallResult.Failure -> carrier
            is WasmlineCallResult.Success -> WasmlineTypedInvocationCodec.decodeRawResult(carrier.value)
        }
    }
}

/** Invokes a typed Component export on this loaded host runtime. */
fun Wasmline.invokeComponentResult(
    exportName: String,
    arguments: List<WasmlineComponentValue> = emptyList(),
): WasmlineCallResult<WasmlineComponentCallResult> {
    val protocolError = validateProtocol(WasmlineInvocationProtocol.COMPONENT_EXPORT, exportName)
    if (protocolError != null) return protocolError

    return invokeComponentTransportResult(exportName, arguments)
}

internal fun Wasmline.invokeComponentTransportResult(
    exportName: String,
    arguments: List<WasmlineComponentValue> = emptyList(),
): WasmlineCallResult<WasmlineComponentCallResult> {
    if (exportName.isBlank()) {
        return failure(WasmlineErrorCode.INVALID_PAYLOAD, "Export name must not be blank.")
    }

    return when (val encoded = WasmlineTypedInvocationCodec.encodeComponentArguments(arguments)) {
        is WasmlineCallResult.Failure -> encoded

        is WasmlineCallResult.Success -> when (val carrier = invokeComponentCarrier(exportName, encoded.value)) {
            is WasmlineCallResult.Failure -> carrier
            is WasmlineCallResult.Success -> WasmlineTypedInvocationCodec.decodeComponentResult(carrier.value)
        }
    }
}

private fun Wasmline.validateProtocol(expected: WasmlineInvocationProtocol, exportName: String): WasmlineCallResult.Failure? {
    if (exportName.isBlank()) {
        return failure(WasmlineErrorCode.INVALID_PAYLOAD, "Export name must not be blank.")
    }
    if (descriptor.invocationProtocol != expected) {
        return failure(
            WasmlineErrorCode.INVOCATION_PROTOCOL_MISMATCH,
            "Artifact protocol ${descriptor.invocationProtocol} cannot use $expected.",
        )
    }
    return null
}

private fun failure(code: WasmlineErrorCode, message: String): WasmlineCallResult.Failure =
    WasmlineCallResult.Failure(WasmlineFailure(code = code, message = message))
