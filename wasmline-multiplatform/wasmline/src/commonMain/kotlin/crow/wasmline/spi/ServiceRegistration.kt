@file:Suppress("unused")

package crow.wasmline.internal.bridge

import crow.wasmline.WasmlineService
import crow.wasmline.WasmlineServiceRegistry
import kotlin.reflect.KClass

/** Install generated typed Wasmline service glue for runtime lookup. */
fun <T : WasmlineService> registerGeneratedService(
    contract: KClass<T>,
    serviceId: String,
    linker: ((String, ByteArray) -> ByteArray) -> T,
    binder: (T, (String, (ByteArray) -> ByteArray) -> Unit) -> Unit,
    identityTag: String,
) {
    WasmlineServiceRegistry.register(contract, serviceId, linker, binder, identityTag)
}

/** Remove previously installed generated typed Wasmline service glue. */
fun unregisterGeneratedService(contract: KClass<out WasmlineService>) {
    WasmlineServiceRegistry.unregister(contract)
}



