package crow.wasmline

import kotlin.reflect.KClass

/**
 * Mutable binding container used to expose local services to an endpoint.
 *
 * Most application code interacts with this through `bindServices { ... }`.
 */
class WasmlineBindingScope {
    private val handlers = linkedMapOf<String, WasmlineActionHandler>()

    fun bind(action: String, handler: WasmlineActionHandler) {
        check(action !in handlers) { "Action '$action' is already bound in this Wasmline binding scope." }
        handlers[action] = handler
    }

    fun invoke(action: String, payload: ByteArray): ByteArray {
        val handler = handlers[action] ?: error("No Wasmline action bound for '$action'.")
        return handler.handle(payload)
    }

    fun endpoint(): WasmlineEndpoint = InMemoryWasmlineEndpoint(handlers.toMap())

    internal fun snapshot(): Map<String, WasmlineActionHandler> = handlers.toMap()
}

fun <T : WasmlineService> WasmlineBindingScope.bind(contract: KClass<T>, implementation: T) {
    WasmlineServiceRegistry.require(contract).bind(implementation, this)
}

/**
 * Bind a local implementation to its uniquely matching registered service contract.
 *
 * If the implementation matches zero contracts, or more than one contract, this
 * fails fast with a descriptive error. Use [bindAs] when the intended contract
 * should be explicit.
 */
fun WasmlineBindingScope.bind(implementation: WasmlineService) {
    val matches = WasmlineServiceRegistry.matching(implementation)
    when (matches.size) {
        0 -> error(
            "No Wasmline service definition matches implementation ${implementation::class.qualifiedName}. " +
                "Did the compiler plugin generate and register its contract definition?",
        )

        1 -> bindUnchecked(matches.single(), implementation)

        else -> error(
            buildString {
                append("Multiple Wasmline service contracts match implementation ")
                append(implementation::class.qualifiedName)
                append(": ")
                append(matches.joinToString { it.contract.qualifiedName ?: it.contract.toString() })
                append(". Use bindAs<Contract>(implementation) or bind(Contract::class, implementation) to disambiguate.")
            },
        )
    }
}

@Suppress("UNCHECKED_CAST")
private fun WasmlineBindingScope.bindUnchecked(
    definition: WasmlineServiceDefinition<out WasmlineService>,
    implementation: WasmlineService,
) {
    (definition as WasmlineServiceDefinition<WasmlineService>).bind(implementation, this)
}

/** Bind a local implementation as the explicitly selected service contract. */
inline fun <reified T : WasmlineService> WasmlineBindingScope.bindAs(implementation: WasmlineService) {
    check(T::class.isInstance(implementation)) {
        "Implementation ${implementation::class.qualifiedName} is not an instance of service contract ${T::class.qualifiedName}."
    }
    bind(T::class, implementation as T)
}

