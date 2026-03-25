package crow.wasmline

import crow.wasmline.spi.ServiceDefinition
import kotlin.reflect.KClass

/**
 * Runtime registry of generated service definitions.
 *
 * This is internal runtime wiring for typed Wasmline services.
 * Typical callers should stay on the public `Wasmline.bind(...)` / `Wasmline.link<T>()` facade APIs.
 */
internal object WasmlineServiceRegistry {
    private val definitions = mutableMapOf<KClass<out WasmlineService>, ServiceDefinition<out WasmlineService>>()

    fun register(definition: ServiceDefinition<out WasmlineService>) {
        val contract = definition.contract
        val existing = definitions[contract]
        when {
            existing == null -> definitions[contract] = definition

            existing === definition -> Unit

            existing.serviceId == definition.serviceId && existing::class == definition::class -> Unit

            else -> error(
                buildString {
                    append("Conflicting Wasmline service definition registration for ")
                    append(contract.qualifiedName ?: contract.toString())
                    append(". Existing=")
                    append(existing::class.qualifiedName ?: existing::class.toString())
                    append("[")
                    append(existing.serviceId.value)
                    append("]")
                    append(", new=")
                    append(definition::class.qualifiedName ?: definition::class.toString())
                    append("[")
                    append(definition.serviceId.value)
                    append("]")
                    append('.')
                },
            )
        }
    }

    fun unregister(contract: KClass<out WasmlineService>) {
        definitions.remove(contract)
    }

    internal fun matching(implementation: WasmlineService): List<ServiceDefinition<out WasmlineService>> {
        return definitions.values.filter { definition ->
            definition.contract.isInstance(implementation)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : WasmlineService> require(contract: KClass<T>): ServiceDefinition<T> {
        return definitions[contract] as? ServiceDefinition<T>
            ?: error(
                "No Wasmline service definition registered for ${contract.qualifiedName ?: contract.toString()}. " +
                    "Did the compiler plugin generate and register it?",
            )
    }
}