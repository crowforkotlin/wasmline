package crow.wasmline

import kotlinx.coroutines.runBlocking

fun Wasmline.asEndpoint(): WasmlineEndpoint {
    return object : WasmlineEndpoint {
        override fun invoke(action: String, payload: ByteArray): ByteArray = runBlocking { call(action, payload) }
    }
}

inline fun <reified T : WasmlineService> Wasmline.link(): T {
    return asEndpoint().link<T>()
}

fun WasmlineBindingScope.toHostDispatcher(): WasmlineHostDispatcher {
    return WasmlineHostDispatcher { action, payload -> invoke(action, payload) }
}

suspend fun Wasmline.bindServices(block: WasmlineBindingScope.() -> Unit) {
    val scope = WasmlineBindingScope().apply(block)
    setOutbound(scope.toHostDispatcher())
}

