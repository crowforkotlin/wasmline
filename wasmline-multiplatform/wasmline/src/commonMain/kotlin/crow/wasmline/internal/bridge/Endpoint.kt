package crow.wasmline.internal.bridge

/** Low-level action/payload transport endpoint used by generated bridge code. */
internal interface WasmlineEndpoint {
    fun invoke(action: String, payload: ByteArray): ByteArray
}

internal class InMemoryWasmlineEndpoint(
    private val handlers: Map<String, (ByteArray) -> ByteArray>,
) : WasmlineEndpoint {
    override fun invoke(action: String, payload: ByteArray): ByteArray {
        val handler = handlers[action] ?: error("No Wasmline action bound for '$action'.")
        return handler(payload)
    }
}

