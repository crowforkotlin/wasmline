/**
 * Adapts the Wasmline byte protocol to the fixed wasmline:rpc Component interface.
 *
 * Date: 2026-08-05
 * Author: crowforkotlin
 */
package crow.wasmline

import crow.wasmline.invocation.WasmlineCallError
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode

internal object WasmlineComponentRpc {
    const val DEFAULT_EXPORT = WasmlineComponentRpcContract.DEFAULT_EXPORT

    fun invoke(wasmline: Wasmline, action: String, payload: ByteArray): WasmlineCallResult<ByteArray> {
        contractFailure(wasmline)?.let { return it }
        val request = WasmlineComponentValue.RecordValue(
            listOf(
                WasmlineComponentValue.RecordField("action", WasmlineComponentValue.StringValue(action)),
                WasmlineComponentValue.RecordField(
                    "codec",
                    WasmlineComponentValue.StringValue(wasmline.config.serialization.factoryId),
                ),
                WasmlineComponentValue.RecordField("payload", payload.toComponentBytes()),
            ),
        )
        return when (
            val result = wasmline.invokeComponentTransportResult(
                exportName = DEFAULT_EXPORT,
                arguments = listOf(request),
            )
        ) {
            is WasmlineCallResult.Failure -> result
            is WasmlineCallResult.Success -> decode(result.value)
        }
    }

    private fun contractFailure(wasmline: Wasmline): WasmlineCallResult.Failure? {
        val metadata = wasmline.descriptor.contractMetadata
        val version = metadata[WasmlineComponentRpcContract.METADATA_VERSION]
        if (version != null && version != WasmlineComponentRpcContract.VERSION) {
            return WasmlineCallResult.Failure(
                WasmlineCallError(
                    code = WasmlineErrorCode.RESPONSE_UNSUPPORTED_VERSION,
                    message = "Unsupported Wasmline Component RPC version '$version'.",
                ),
            )
        }
        val profile = metadata[WasmlineComponentRpcContract.METADATA_PROFILE]
        if (profile != null && profile != WasmlineComponentRpcContract.PROFILE) {
            return WasmlineCallResult.Failure(
                WasmlineCallError(
                    code = WasmlineErrorCode.INVOCATION_PROTOCOL_MISMATCH,
                    message = "Unsupported Wasmline Component RPC profile '$profile'.",
                ),
            )
        }
        val codec = metadata[WasmlineComponentRpcContract.METADATA_CODEC]
        if (codec != null && codec != wasmline.config.serialization.factoryId) {
            return WasmlineCallResult.Failure(
                WasmlineCallError(
                    code = WasmlineErrorCode.SERIALIZATION_FAILED,
                    message = "Wasmline Component RPC codec mismatch. Expected '$codec' but host uses '" +
                        wasmline.config.serialization.factoryId + "'.",
                ),
            )
        }
        return null
    }

    internal fun decode(result: WasmlineComponentCallResult): WasmlineCallResult<ByteArray> {
        if (result.values.size != 1) return malformed("Wasmline Component invoke must return exactly one result.")
        val rpcResult = result.values.single() as? WasmlineComponentValue.ResultValue
            ?: return malformed("Wasmline Component invoke result is not a WIT result value.")
        val value = rpcResult.value ?: return malformed("Wasmline Component invoke result has no payload.")
        return if (rpcResult.isOk) {
            value.toByteArrayOrNull()?.let { WasmlineCallResult.Success(value = it) }
                ?: malformed("Wasmline Component success payload is not list<u8>.")
        } else {
            decodeError(value)
        }
    }

    private fun decodeError(value: WasmlineComponentValue): WasmlineCallResult.Failure {
        val record = value as? WasmlineComponentValue.RecordValue
            ?: return malformed("Wasmline Component error payload is not rpc-error.")
        val code = record.field("code") as? WasmlineComponentValue.StringValue
            ?: return malformed("Wasmline Component rpc-error.code is not a string.")
        val message = record.field("message") as? WasmlineComponentValue.StringValue
            ?: return malformed("Wasmline Component rpc-error.message is not a string.")
        val details = record.field("details")?.toByteArrayOrNull()
            ?: return malformed("Wasmline Component rpc-error.details is not list<u8>.")
        val rawCode = code.value.toIntOrNull()
        val errorCode = rawCode?.let(WasmlineErrorCode::fromValue) ?: code.value.toErrorCode()
        return WasmlineCallResult.Failure(
            WasmlineCallError(
                code = errorCode,
                message = message.value,
                details = details,
                rawCode = rawCode ?: errorCode.value,
            ),
        )
    }

    private fun malformed(message: String): WasmlineCallResult.Failure = WasmlineCallResult.Failure(
        WasmlineCallError(WasmlineErrorCode.RESPONSE_MALFORMED, message),
    )
}

private fun ByteArray.toComponentBytes(): WasmlineComponentValue.ListValue = WasmlineComponentValue.ListValue(
    map { WasmlineComponentValue.U8(it.toUByte()) },
)

private fun WasmlineComponentValue.toByteArrayOrNull(): ByteArray? {
    val list = this as? WasmlineComponentValue.ListValue ?: return null
    val bytes = ByteArray(list.values.size)
    for ((index, value) in list.values.withIndex()) {
        val byte = (value as? WasmlineComponentValue.U8)?.value?.toByte() ?: return null
        bytes[index] = byte
    }
    return bytes
}

private fun WasmlineComponentValue.RecordValue.field(name: String): WasmlineComponentValue? = fields
    .firstOrNull { it.name == name }
    ?.value

private fun String.toErrorCode(): WasmlineErrorCode {
    val enumName = uppercase().replace('-', '_')
    return WasmlineErrorCode.entries.firstOrNull { it.name == enumName } ?: WasmlineErrorCode.UNKNOWN
}
