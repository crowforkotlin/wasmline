package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineEndpoint
import crow.wasmline.invocation.WasmlineCallError
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode

/** Marks the narrow runtime surface used by Wasmline-owned guest transports. */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "This API is reserved for Wasmline-generated transport adapters.",
)
@Retention(AnnotationRetention.BINARY)
annotation class WasmlineTransportApi

/** Marks the generated Wasmline Service initializer that the compiler wires to user `main()`. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@WasmlineTransportApi
annotation class WasmlineComponentServiceInit

/** Outbound fixed-WIT transport installed by the generated Wasmline Service adapter. */
@WasmlineTransportApi
fun interface WasmlineComponentServiceOutboundTransport {
    fun invoke(action: String, codec: String, payload: ByteArray): WasmlineCallResult<ByteArray>
}

/**
 * Guest-side Service runtime used by the generated fixed-WIT Wasmline Service adapter.
 *
 * All fields become instance-local Wasm globals after componentization. Registration and
 * initialization therefore cannot leak between two Component instances.
 */
@WasmlineTransportApi
object WasmlineGuestServiceRuntime {
    private var outboundTransport: WasmlineComponentServiceOutboundTransport? = null
    private var initializationState = InitializationState.UNINITIALIZED
    private var initializationFailure: WasmlineCallError? = null

    val codecId: String
        get() = Wasmline.get().serializationFactory.id

    fun invoke(
        action: String,
        codec: String,
        payload: ByteArray,
        transport: WasmlineComponentServiceOutboundTransport,
        initialize: () -> Unit,
    ): WasmlineCallResult<ByteArray> {
        validateRequest(action, codec, payload)?.let { return WasmlineCallResult.Failure(it) }
        installTransport(transport)?.let { return WasmlineCallResult.Failure(it) }
        ensureInitialized(initialize)?.let { return WasmlineCallResult.Failure(it) }
        return WasmlineRouter.dispatch(action, payload)
    }

    internal fun callHost(action: String, payload: ByteArray): WasmlineCallResult<ByteArray> {
        if (initializationState == InitializationState.INITIALIZING) {
            return failure(
                WasmlineErrorCode.INVOCATION_PROTOCOL_MISMATCH,
                "Wasmline Service does not support Host calls while guest main() is initializing.",
            )
        }
        if (action.encodeToByteArray().size > MAX_ACTION_BYTES) {
            return failure(
                WasmlineErrorCode.INVALID_PAYLOAD,
                "Wasmline Service action exceeds the $MAX_ACTION_BYTES-byte limit.",
            )
        }
        if (payload.size > MAX_PAYLOAD_BYTES) {
            return failure(
                WasmlineErrorCode.INVALID_PAYLOAD,
                "Wasmline Service payload exceeds the $MAX_PAYLOAD_BYTES-byte limit.",
            )
        }
        val transport = outboundTransport ?: return failure(
            WasmlineErrorCode.TRANSPORT_FAILURE,
            "Wasmline Service outbound transport is not installed.",
        )
        return transport.invoke(action, codecId, payload)
    }

    private fun validateRequest(action: String, codec: String, payload: ByteArray): WasmlineCallError? = when {
        action.encodeToByteArray().size > MAX_ACTION_BYTES -> WasmlineCallError(
            code = WasmlineErrorCode.INVALID_PAYLOAD,
            message = "Wasmline Service action exceeds the $MAX_ACTION_BYTES-byte limit.",
        )

        codec.encodeToByteArray().size > MAX_CODEC_BYTES -> WasmlineCallError(
            code = WasmlineErrorCode.INVALID_PAYLOAD,
            message = "Wasmline Service codec id exceeds the $MAX_CODEC_BYTES-byte limit.",
        )

        payload.size > MAX_PAYLOAD_BYTES -> WasmlineCallError(
            code = WasmlineErrorCode.INVALID_PAYLOAD,
            message = "Wasmline Service payload exceeds the $MAX_PAYLOAD_BYTES-byte limit.",
        )

        codec != codecId -> WasmlineCallError(
            code = WasmlineErrorCode.SERIALIZATION_FAILED,
            message = "Unsupported Wasmline Service codec '$codec'. Expected '$codecId'.",
        )

        else -> null
    }

    private fun installTransport(transport: WasmlineComponentServiceOutboundTransport): WasmlineCallError? {
        val installed = outboundTransport
        if (installed == null) {
            outboundTransport = transport
            return null
        }
        return if (installed === transport) {
            null
        } else {
            WasmlineCallError(
                code = WasmlineErrorCode.TRANSPORT_FAILURE,
                message = "A different Wasmline Service outbound transport is already installed.",
            )
        }
    }

    private fun ensureInitialized(initialize: () -> Unit): WasmlineCallError? = when (initializationState) {
        InitializationState.INITIALIZED -> null

        InitializationState.FAILED -> initializationFailure

        InitializationState.INITIALIZING -> WasmlineCallError(
            code = WasmlineErrorCode.INVOCATION_PROTOCOL_MISMATCH,
            message = "Recursive Wasmline Service guest initialization is not supported.",
        )

        InitializationState.UNINITIALIZED -> {
            initializationState = InitializationState.INITIALIZING
            try {
                initialize()
                initializationState = InitializationState.INITIALIZED
                null
            } catch (error: Throwable) {
                val failure = WasmlineCallError(
                    code = WasmlineErrorCode.HANDLER_FAILED,
                    message = error.message?.let { "Wasmline Service guest initialization failed: $it" }
                        ?: "Wasmline Service guest initialization failed.",
                )
                initializationFailure = failure
                initializationState = InitializationState.FAILED
                failure
            }
        }
    }

    private fun failure(code: WasmlineErrorCode, message: String): WasmlineCallResult.Failure =
        WasmlineCallResult.Failure(WasmlineCallError(code = code, message = message))

    private enum class InitializationState {
        UNINITIALIZED,
        INITIALIZING,
        INITIALIZED,
        FAILED,
    }

    const val MAX_ACTION_BYTES: Int = 4 * 1024
    const val MAX_CODEC_BYTES: Int = 128
    const val MAX_PAYLOAD_BYTES: Int = 16 * 1024 * 1024
}

/** Component Service endpoint selected statically by compiler-generated Service proxies. */
@PublishedApi
internal object GeneratedWasmlineComponentServiceEndpoint : WasmlineEndpoint {
    override fun invoke(action: String, payload: ByteArray): ByteArray = invokeResult(action, payload).throwOnFailure()

    @OptIn(WasmlineTransportApi::class)
    override fun invokeResult(action: String, payload: ByteArray): WasmlineCallResult<ByteArray> =
        WasmlineGuestServiceRuntime.callHost(action, payload)
}
