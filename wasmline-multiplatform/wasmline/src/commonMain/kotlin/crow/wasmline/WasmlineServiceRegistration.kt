@file:Suppress("unused")

package crow.wasmline

import kotlin.reflect.KClass

/**
 * Advanced bootstrap hook for making a generated Wasmline service definition
 * available to the typed `link<T>()` / `bind(...)` APIs.
 *
 * Typical application code should not need this directly; it exists so generated
 * glue or explicit bootstrap code can install definitions without exposing the
 * registry object itself.
 */
fun registerWasmlineServiceDefinition(definition: WasmlineServiceDefinition<out WasmlineService>) {
    WasmlineServiceRegistry.register(definition)
}

/**
 * Remove a previously installed service definition.
 *
 * This is primarily useful for tests and explicit bootstrap teardown.
 */
fun unregisterWasmlineServiceDefinition(contract: KClass<out WasmlineService>) {
    WasmlineServiceRegistry.unregister(contract)
}

