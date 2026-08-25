package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineGeneratedBridge
import crow.wasmline.internal.bridge.WasmlineHostDispatcher
import crow.wasmline.internal.protocol.WasmlineResponseCodec
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineFailure

/** Instance-owned dispatcher registry shared by Core and Wasmline Service callbacks. */
internal class WasmlineHostServiceRegistry {
    private val lock = WasmlineHostServiceLock()
    private var state = State()

    val dispatcher: WasmlineHostDispatcher = WasmlineHostDispatcher(::dispatch)

    /** Atomically registers every action exposed by one generated service bridge. */
    fun registerAll(bridge: WasmlineGeneratedBridge): Boolean {
        val collected = linkedMapOf<String, (ByteArray) -> ByteArray>()
        bridge.bind { action, handler ->
            check(action !in collected) {
                "Generated service bridge declares duplicate action '$action'."
            }
            collected[action] = handler
        }
        val contractId = collected.keys.firstOrNull()?.substringBeforeLast('#') ?: "<empty-service>"
        val additions = collected.mapValues { (_, handler) -> Handler(contractId, handler) }

        return lock.withLock {
            val current = state
            checkCanRegister(current, "generated service '$contractId'")
            check(current.mode != OwnershipMode.RAW) {
                "Cannot bind generated service '$contractId' after a raw Wasmline Service handler was selected."
            }
            val conflict = additions.keys.firstOrNull(current.handlers::containsKey)
            check(conflict == null) {
                val existing = current.handlers.getValue(requireNotNull(conflict))
                "Action '$conflict' from generated service '$contractId' is already bound by '${existing.contractId}'."
            }
            val next = current.copy(
                mode = OwnershipMode.GENERATED,
                handlers = current.handlers + additions,
                dispatcherInstalled = true,
            )
            state = next
            !current.dispatcherInstalled
        }
    }

    /** Selects the mutually exclusive raw Wasmline Service ownership mode. */
    fun registerRaw(handler: (String, ByteArray) -> WasmlineCallResult<ByteArray>): Boolean = lock.withLock {
        val current = state
        checkCanRegister(current, "raw Wasmline Service handler")
        check(current.mode != OwnershipMode.GENERATED) {
            "Cannot bind a raw Wasmline Service handler after generated Wasmline services were selected."
        }
        check(current.rawHandler == null) { "A raw Wasmline Service handler is already bound to this Wasmline instance." }
        val next = current.copy(
            mode = OwnershipMode.RAW,
            rawHandler = handler,
            dispatcherInstalled = true,
        )
        state = next
        !current.dispatcherInstalled
    }

    fun clear() {
        lock.withLock { state = State(closed = true) }
    }

    private fun dispatch(action: String, payload: ByteArray): ByteArray {
        val snapshot = freezeAndSnapshot()
        if (snapshot.closed) return failure(WasmlineErrorCode.ACTION_NOT_BOUND, "Wasmline instance is closed.")
        snapshot.rawHandler?.let { handler ->
            return try {
                when (val result = handler(action, payload)) {
                    is WasmlineCallResult.Success -> WasmlineResponseCodec.encodeSuccess(result.value)
                    is WasmlineCallResult.Failure -> WasmlineResponseCodec.encodeFailure(result.failure)
                }
            } catch (error: Throwable) {
                failure(WasmlineErrorCode.HANDLER_FAILED, error.message ?: "Wasmline action handler failed.")
            }
        }

        val handler = snapshot.handlers[action]
        if (handler == null) {
            return if (snapshot.handlers.isEmpty()) {
                failure(WasmlineErrorCode.ACTION_NOT_BOUND, "No Wasmline action is bound.")
            } else {
                failure(WasmlineErrorCode.UNKNOWN_ACTION, "Wasmline action is not registered: $action.")
            }
        }
        return try {
            WasmlineResponseCodec.encodeSuccess(handler.invoke(payload))
        } catch (error: Throwable) {
            failure(WasmlineErrorCode.HANDLER_FAILED, error.message ?: "Wasmline action handler failed.")
        }
    }

    private fun freezeAndSnapshot(): State = lock.withLock {
        val current = state
        if (!current.frozen && !current.closed) {
            state = current.copy(frozen = true)
        }
        state
    }

    private fun checkCanRegister(current: State, description: String) {
        check(!current.closed) { "Cannot bind $description after the Wasmline instance was closed." }
        check(!current.frozen) { "Cannot bind $description after the first invocation." }
    }

    private fun failure(code: WasmlineErrorCode, message: String): ByteArray =
        WasmlineResponseCodec.encodeFailure(WasmlineFailure(code, message))

    private data class Handler(val contractId: String, val invoke: (ByteArray) -> ByteArray)

    private data class State(
        val mode: OwnershipMode = OwnershipMode.UNBOUND,
        val handlers: Map<String, Handler> = emptyMap(),
        val rawHandler: ((String, ByteArray) -> WasmlineCallResult<ByteArray>)? = null,
        val dispatcherInstalled: Boolean = false,
        val frozen: Boolean = false,
        val closed: Boolean = false,
    )

    private enum class OwnershipMode {
        UNBOUND,
        GENERATED,
        RAW,
    }
}

internal expect class WasmlineHostServiceLock() {
    fun <T> withLock(block: () -> T): T
}
