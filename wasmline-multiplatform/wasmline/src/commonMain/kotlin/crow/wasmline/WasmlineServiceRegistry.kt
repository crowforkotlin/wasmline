package crow.wasmline

import kotlin.reflect.KClass

/**
 * Runtime registry of generated service definitions.
 *
 * Typical callers do not need to touch this directly; use `link<T>()` and
 * `bindServices { ... }` where possible.
 */
object WasmlineServiceRegistry {
    private val definitions = mutableMapOf<KClass<out WasmlineService>, WasmlineServiceDefinition<out WasmlineService>>()

    fun register(definition: WasmlineServiceDefinition<out WasmlineService>) {
        definitions[definition.contract] = definition
    }

    fun unregister(contract: KClass<out WasmlineService>) {
        definitions.remove(contract)
    }

    internal fun matching(implementation: WasmlineService): List<WasmlineServiceDefinition<out WasmlineService>> {
        return definitions.values.filter { definition ->
            definition.contract.isInstance(implementation)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : WasmlineService> require(contract: KClass<T>): WasmlineServiceDefinition<T> {
        return definitions[contract] as? WasmlineServiceDefinition<T>
            ?: error(
                "No Wasmline service definition registered for ${contract.qualifiedName ?: contract.toString()}. " +
                    "Did the compiler plugin generate and register it?",
            )
    }
}