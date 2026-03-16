package crow.wasmline

fun interface WasmlineActionHandler {
    fun handle(payload: ByteArray): ByteArray
}

interface WasmlineEndpoint {
    fun invoke(action: String, payload: ByteArray): ByteArray
}

internal class InMemoryWasmlineEndpoint(
    private val handlers: Map<String, WasmlineActionHandler>,
) : WasmlineEndpoint {
    override fun invoke(action: String, payload: ByteArray): ByteArray {
        val handler = handlers[action] ?: error("No Wasmline action bound for '$action'.")
        return handler.handle(payload)
    }
}

