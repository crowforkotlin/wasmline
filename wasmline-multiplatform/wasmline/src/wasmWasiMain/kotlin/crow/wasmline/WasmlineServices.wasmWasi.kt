package crow.wasmline

object WasmlineHostEndpoint : WasmlineEndpoint {
    override fun invoke(action: String, payload: ByteArray): ByteArray {
        return WasmBridge.callHost(action, payload)
    }
}

inline fun <reified T : WasmlineService> linkHost(): T {
    return WasmlineHostEndpoint.link<T>()
}

fun bindServices(block: WasmlineBindingScope.() -> Unit) {
    val scope = WasmlineBindingScope().apply(block)
    for ((action, handler) in scope.snapshot()) {
        WasmRouter.register(action) { payload ->
            handler.handle(payload ?: ByteArray(0))
        }
    }
}

