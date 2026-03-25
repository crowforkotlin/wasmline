package crow.wasmline.spi

fun interface WasmlineActionHandler {
    fun handle(payload: ByteArray): ByteArray
}

/**
 * Low-level action/payload transport endpoint.
 *
 * Typical application code reaches this through higher-level helpers such as
 * `Wasmline.link<T>()`, `WasmlineEndpoint.link<T>()`, or `bindServices { ... }`.
 */
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



