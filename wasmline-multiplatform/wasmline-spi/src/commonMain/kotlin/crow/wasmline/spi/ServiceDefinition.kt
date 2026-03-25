package crow.wasmline.spi

import crow.wasmline.WasmlineService
import kotlin.reflect.KClass

/**
 * Generated-code SPI for one Wasmline service contract.
 *
 * This package is runtime glue for compiler-generated code, not the main user API.
 */
interface ServiceDefinition<T : WasmlineService> {
    val contract: KClass<T>
    val serviceId: ServiceId

    fun link(endpoint: WasmlineEndpoint): T

    fun bind(implementation: T, scope: WasmlineBindingScope)
}


