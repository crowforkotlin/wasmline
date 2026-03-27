package crow.wasmline

import kotlin.reflect.KClass

internal class RegisteredServiceEntry<T : WasmlineService>(
    val contract: KClass<T>,
    val serviceId: String,
    private val linker: ((String, ByteArray) -> ByteArray) -> T,
    private val binder: (T, (String, (ByteArray) -> ByteArray) -> Unit) -> Unit,
    private val identityTag: String,
) {
    fun link(invokeAction: (String, ByteArray) -> ByteArray): T = linker(invokeAction)

    fun bind(implementation: T, registerAction: (String, (ByteArray) -> ByteArray) -> Unit) {
        binder(implementation, registerAction)
    }

    fun sameRegistration(other: RegisteredServiceEntry<*>): Boolean {
        return serviceId == other.serviceId && identityTag == other.identityTag
    }
}

/**
 * Runtime registry of generated service definitions.
 *
 * This is internal runtime wiring for typed Wasmline services.
 * Typical callers should stay on the public `Wasmline.bind(...)` / `Wasmline.link<T>()` facade APIs.
 */
internal object WasmlineServiceRegistry {
    private val definitions = mutableMapOf<KClass<out WasmlineService>, RegisteredServiceEntry<out WasmlineService>>()

    fun <T : WasmlineService> register(
        contract: KClass<T>,
        serviceId: String,
        linker: ((String, ByteArray) -> ByteArray) -> T,
        binder: (T, (String, (ByteArray) -> ByteArray) -> Unit) -> Unit,
        identityTag: String,
    ) {
        val entry = RegisteredServiceEntry(
            contract = contract,
            serviceId = serviceId,
            linker = linker,
            binder = binder,
            identityTag = identityTag,
        )
        val contract = entry.contract
        val existing = definitions[contract]
        when {
            existing == null -> definitions[contract] = entry

            existing === entry -> Unit

            existing.sameRegistration(entry) -> Unit

            else -> error(
                buildString {
                    append("Conflicting Wasmline service definition registration for ")
                    append(contract.qualifiedName ?: contract.toString())
                    append(". Existing=")
                    append(existing.contract.qualifiedName ?: existing.contract.toString())
                    append("[")
                    append(existing.serviceId)
                    append("]")
                    append(", new=")
                    append(entry.contract.qualifiedName ?: entry.contract.toString())
                    append("[")
                    append(entry.serviceId)
                    append("]")
                    append('.')
                },
            )
        }
    }

    fun unregister(contract: KClass<out WasmlineService>) {
        definitions.remove(contract)
    }

    internal fun matching(implementation: WasmlineService): List<RegisteredServiceEntry<out WasmlineService>> {
        return definitions.values.filter { definition ->
            definition.contract.isInstance(implementation)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : WasmlineService> require(contract: KClass<T>): RegisteredServiceEntry<T> {
        return definitions[contract] as? RegisteredServiceEntry<T>
            ?: error(
                "No Wasmline service definition registered for ${contract.qualifiedName ?: contract.toString()}. " +
                    "Did the compiler plugin generate and register it?",
            )
    }
}