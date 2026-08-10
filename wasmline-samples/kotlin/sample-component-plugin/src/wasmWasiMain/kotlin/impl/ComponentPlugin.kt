package impl

import bindings.Host
import bindings.Plugin
import bindings.runtime.ComponentException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf

/** Implements the exported wasmline:rpc plugin interface. */
object PluginImpl : Plugin {
    override fun invoke(request: Plugin.Request): Result<List<UByte>> {
        if (request.codec != PROTOBUF_CODEC) {
            return rpcFailure(
                code = SERIALIZATION_FAILED,
                message = "Unsupported codec '${request.codec}'. Expected '$PROTOBUF_CODEC'.",
            )
        }

        return when (request.action) {
            ACTION_ECHO -> echo(request.payload)
            ACTION_CALLBACK -> callbackHost(request)
            ACTION_EMPTY -> Result.success(emptyList())
            ACTION_TRAP -> error("Intentional Component trap from the sample plugin.")
            else -> rpcFailure(UNKNOWN_ACTION, "Unknown sample Component action: ${request.action}.")
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun echo(payload: List<UByte>): Result<List<UByte>> = runCatching {
        val request = ProtoBuf.decodeFromByteArray(EchoRequest.serializer(), payload.toByteArray())
        ProtoBuf.encodeToByteArray(EchoResponse.serializer(), EchoResponse("plugin:${request.value}")).toUByteList()
    }.mapFailure(INVALID_PAYLOAD, "Unable to decode the echo request.")

    private fun callbackHost(request: Plugin.Request): Result<List<UByte>> = Host.Import.invoke(
        Host.Request(
            action = HOST_CALLBACK_ACTION,
            codec = request.codec,
            payload = request.payload,
        ),
    ).fold(
        onSuccess = { Result.success(it) },
        onFailure = { error -> Result.failure(ComponentException(error.toPluginError())) },
    )

    private fun rpcFailure(code: Int, message: String, details: List<UByte> = emptyList()): Result<List<UByte>> =
        Result.failure(
            ComponentException(
                Plugin.RpcError(
                    code = code.toString(),
                    message = message,
                    details = details,
                ),
            ),
        )
}

@Serializable
data class EchoRequest(val value: String)

@Serializable
data class EchoResponse(val value: String)

private fun Throwable.toPluginError(): Plugin.RpcError {
    val hostError = (this as? ComponentException)?.value as? Host.RpcError
    return if (hostError == null) {
        Plugin.RpcError(HANDLER_FAILED.toString(), message ?: "Host callback failed.", emptyList())
    } else {
        Plugin.RpcError(hostError.code, hostError.message, hostError.details)
    }
}

private fun <T> Result<T>.mapFailure(code: Int, fallbackMessage: String): Result<T> = fold(
    onSuccess = { Result.success(it) },
    onFailure = { error ->
        Result.failure(
            ComponentException(
                Plugin.RpcError(code.toString(), error.message ?: fallbackMessage, emptyList()),
            ),
        )
    },
)

private fun List<UByte>.toByteArray(): ByteArray = ByteArray(size) { index -> this[index].toByte() }

private fun ByteArray.toUByteList(): List<UByte> = map(Byte::toUByte)

private const val PROTOBUF_CODEC = "protobuf"
private const val ACTION_ECHO = "sample.echo"
private const val ACTION_CALLBACK = "sample.callback"
private const val ACTION_EMPTY = "sample.empty"
private const val ACTION_TRAP = "sample.trap"
private const val HOST_CALLBACK_ACTION = "sample.host.callback"

private const val UNKNOWN_ACTION = 1002
private const val INVALID_PAYLOAD = 1003
private const val HANDLER_FAILED = 1004
private const val SERIALIZATION_FAILED = 1005
