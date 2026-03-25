@file:Suppress("unused")

package crow.wasmline.spi

import crow.wasmline.WasmlineService
import crow.wasmline.WasmlineServiceRegistry
import kotlin.reflect.KClass

/** Install a generated service definition for typed bind/link runtime lookup. */
fun registerServiceDefinition(definition: ServiceDefinition<out WasmlineService>) {
    WasmlineServiceRegistry.register(definition)
}

/** Remove a previously installed generated service definition. */
fun unregisterServiceDefinition(contract: KClass<out WasmlineService>) {
    WasmlineServiceRegistry.unregister(contract)
}



