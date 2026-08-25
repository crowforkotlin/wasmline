package crow.wasmline

import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineFailure
internal fun interface Callback {
    fun callback(params: ByteArray?): ByteArray
}

internal object WasmlineRouter {
    private val handlers = mutableMapOf<String, Callback>()

    internal fun register(action: String, callback: Callback) {
        registerAll(mapOf(action to callback))
    }

    internal fun registerAll(additions: Map<String, Callback>) {
        val conflict = additions.keys.firstOrNull(handlers::containsKey)
        check(conflict == null) { "Wasmline action '$conflict' is already registered in this guest instance." }
        handlers.putAll(additions)
    }

    internal fun clear() {
        handlers.clear()
    }

    internal fun dispatch(action: String?, args: ByteArray?): WasmlineCallResult<ByteArray> {
        if (handlers.isEmpty()) {
            return WasmlineCallResult.Failure(
                WasmlineFailure(
                    code = WasmlineErrorCode.ACTION_NOT_BOUND,
                    message = "No Wasmline action is bound.",
                ),
            )
        }

        val handler = handlers[action]
            ?: return WasmlineCallResult.Failure(
                WasmlineFailure(
                    code = WasmlineErrorCode.UNKNOWN_ACTION,
                    message = "Wasmline action is not registered: ${action.orEmpty()}.",
                ),
            )

        return try {
            WasmlineCallResult.Success(handler.callback(args))
        } catch (error: Throwable) {
            WasmlineCallResult.Failure(
                WasmlineFailure(
                    code = WasmlineErrorCode.HANDLER_FAILED,
                    message = error.message ?: "Wasmline action handler failed.",
                ),
            )
        }
    }
}
