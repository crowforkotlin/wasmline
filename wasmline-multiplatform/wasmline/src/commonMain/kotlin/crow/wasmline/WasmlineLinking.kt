package crow.wasmline

import kotlin.reflect.KClass

/** Create a local proxy that links this endpoint to the remote service contract. */
fun <T : WasmlineService> WasmlineEndpoint.link(contract: KClass<T>): T {
    return WasmlineServiceRegistry.require(contract).link(this)
}

inline fun <reified T : WasmlineService> WasmlineEndpoint.link(): T {
    return link(T::class)
}

