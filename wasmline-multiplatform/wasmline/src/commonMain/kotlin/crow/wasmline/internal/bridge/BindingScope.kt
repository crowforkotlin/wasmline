package crow.wasmline.internal.bridge

/** Mutable binding container used by generated Wasmline bridge code. */
internal class WasmlineBindingScope internal constructor() {
    private val handlers = linkedMapOf<String, (ByteArray) -> ByteArray>()

    fun bind(action: String, handler: (ByteArray) -> ByteArray) {
        check(action !in handlers) { "Action '$action' is already bound in this Wasmline binding scope." }
        handlers[action] = handler
    }

    fun invoke(action: String, payload: ByteArray): ByteArray {
        val handler = handlers[action] ?: error("No Wasmline action bound for '$action'.")
        return handler(payload)
    }

    fun endpoint(): WasmlineEndpoint = InMemoryWasmlineEndpoint(handlers.toMap())

    fun snapshot(): Map<String, (ByteArray) -> ByteArray> = handlers.toMap()
}

