package crow.wasmline

import kotlin.jvm.JvmInline
import kotlin.reflect.KClass

/**
 * Marker interface for first-phase typed RPC contracts.
 *
 * First-phase contract restrictions are intentionally conservative:
 * - the contract must be an `interface`
 * - members should be public functions
 * - overloads are not supported yet
 * - properties are not supported yet
 * - generic contracts and generic functions are not supported yet
 * - `suspend`, default arguments, and `vararg` are not supported yet
 *
 * The compiler plugin is expected to validate these rules and generate the
 * matching Wasmline definition / proxy / adapter glue.
 */
interface WasmlineService

@JvmInline
value class WasmlineServiceId(val value: String)

@JvmInline
value class WasmlineMethodId(val value: String)

data class WasmlineAction(
    val service: WasmlineServiceId,
    val method: WasmlineMethodId,
) {
    val value: String get() = "${service.value}#${method.value}"
}

/**
 * Runtime-facing description produced by generated code for one service contract.
 *
 * In the first phase the compiler plugin is expected to generate one definition
 * per service contract. This definition is the bridge between runtime binding
 * (`bind`, `bindAs`) and remote linking (`link`).
 */
interface WasmlineServiceDefinition<T : WasmlineService> {
    val contract: KClass<T>
    val serviceId: WasmlineServiceId

    /** Create a local proxy that forwards calls to [endpoint]. */
    fun link(endpoint: WasmlineEndpoint): T

    /** Install local handlers for [implementation] into [scope]. */
    fun bind(implementation: T, scope: WasmlineBindingScope)
}

object WasmlineServices {
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
    WasmlineServices.require(contract).bind(implementation, this)
}

/**
 * Bind a local implementation to its uniquely matching registered service contract.
 *
 * If the implementation matches zero contracts, or more than one contract, this
 * fails fast with a descriptive error. Use [bindAs] when the intended contract
 * should be explicit.
 */
fun WasmlineBindingScope.bind(implementation: WasmlineService) {
    val matches = WasmlineServices.matching(implementation)
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

/** Create a local proxy that links this endpoint to the remote service contract. */
fun <T : WasmlineService> WasmlineEndpoint.link(contract: KClass<T>): T {
    return WasmlineServices.require(contract).link(this)
}

inline fun <reified T : WasmlineService> WasmlineEndpoint.link(): T {
    return link(T::class)
}

