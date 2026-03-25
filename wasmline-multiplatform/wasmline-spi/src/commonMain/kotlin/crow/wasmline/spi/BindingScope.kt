package crow.wasmline.spi

/**
 * Mutable binding container used to expose local services to an endpoint.
 *
 * Most application code interacts with this through `bindServices { ... }`.
 * Direct action binding is an advanced escape hatch that sits below the typed
 * `WasmlineService` layer.
 */
class WasmlineBindingScope {
    private val handlers = linkedMapOf<String, WasmlineActionHandler>()

    fun bind(action: String, handler: WasmlineActionHandler) {
        check(action !in handlers) { "Action '$action' is already bound in this Wasmline binding scope." }
        handlers[action] = handler
    }

    fun invoke(action: String, payload: ByteArray): ByteArray {
        val handler = handlers[action] ?: error("No Wasmline action bound for '$action'.")
        return handler.handle(payload)
    }

    fun endpoint(): WasmlineEndpoint = InMemoryWasmlineEndpoint(handlers.toMap())

    fun snapshot(): Map<String, WasmlineActionHandler> = handlers.toMap()
}




