/**
 * Identifiers for typed Component Model host imports.
 *
 * Date: 2026-08-07
 * Author: crowforkotlin
 */
package crow.wasmline

import crow.wasmline.invocation.WasmlineCallResult

/**
 * Identifies one imported Component interface instance.
 *
 * The value is intentionally opaque. Wasmline preserves the exact Component import text instead
 * of attempting to parse every current and future WIT package, resource, or version syntax.
 */
class WasmlineComponentInterfaceId private constructor(val value: String) {
    override fun equals(other: Any?): Boolean = other is WasmlineComponentInterfaceId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        fun of(value: String): WasmlineComponentInterfaceId = WasmlineComponentInterfaceId(
            requireComponentIdentifier(value, "Component interface identifier"),
        )
    }
}

/** Identifies one function imported through a [WasmlineComponentInterfaceId]. */
class WasmlineComponentFunctionId private constructor(val interfaceId: WasmlineComponentInterfaceId, val functionName: String) {
    override fun equals(other: Any?): Boolean = other is WasmlineComponentFunctionId &&
        interfaceId == other.interfaceId &&
        functionName == other.functionName

    override fun hashCode(): Int = 31 * interfaceId.hashCode() + functionName.hashCode()

    override fun toString(): String = "$interfaceId/$functionName"

    companion object {
        fun of(interfaceId: WasmlineComponentInterfaceId, functionName: String): WasmlineComponentFunctionId = WasmlineComponentFunctionId(
            interfaceId = interfaceId,
            functionName = requireComponentIdentifier(functionName, "Component function identifier"),
        )
    }
}

private fun requireComponentIdentifier(value: String, label: String): String {
    require(value.isNotBlank()) { "$label must not be blank." }
    require(value == value.trim()) { "$label must not have leading or trailing whitespace." }
    require(value.none(Char::isWhitespace)) { "$label must not contain whitespace." }
    return value
}

/** Handles one typed Component Model import without introducing an envelope codec. */
fun interface WasmlineComponentHostAdapter {
    fun invoke(arguments: List<WasmlineComponentValue>): WasmlineCallResult<List<WasmlineComponentValue>>
}

/**
 * Immutable lookup table for typed Component Model host adapters.
 *
 * Build a registry before associating it with a loaded Component. A completed registry is an
 * independent snapshot, so later changes to its [Builder] cannot alter an active Component.
 */
class WasmlineComponentHostRegistry private constructor(
    internal val adapters: Map<WasmlineComponentFunctionId, WasmlineComponentHostAdapter>,
    internal val resources: Map<WasmlineComponentResourceId, WasmlineComponentHostResourceBinding>,
) {
    /** Returns the adapter registered for [functionId], or null when the import is not bound. */
    fun lookup(functionId: WasmlineComponentFunctionId): WasmlineComponentHostAdapter? = adapters[functionId]

    /** Returns whether [functionId] has an adapter in this immutable snapshot. */
    operator fun contains(functionId: WasmlineComponentFunctionId): Boolean = functionId in adapters

    /** Number of registered host functions. */
    val size: Int
        get() = adapters.size

    /** Mutable construction scope for one immutable [WasmlineComponentHostRegistry] snapshot. */
    class Builder {
        private val adapters = linkedMapOf<WasmlineComponentFunctionId, WasmlineComponentHostAdapter>()
        private val resources = linkedMapOf<WasmlineComponentResourceId, WasmlineComponentHostResourceBinding>()

        /** Registers [adapter], rejecting ambiguous duplicate function identifiers. */
        fun register(functionId: WasmlineComponentFunctionId, adapter: WasmlineComponentHostAdapter): Builder {
            check(functionId !in adapters) { "Component host adapter is already registered: $functionId." }
            adapters[functionId] = adapter
            return this
        }

        /** Merges an immutable registry, rejecting duplicate function identifiers. */
        fun registerAll(registry: WasmlineComponentHostRegistry): Builder {
            registry.adapters.forEach { (functionId, adapter) -> register(functionId, adapter) }
            registry.resources.forEach { (resourceId, binding) -> registerResource(resourceId, binding) }
            return this
        }

        fun registerResource(resourceId: WasmlineComponentResourceId, binding: WasmlineComponentHostResourceBinding): Builder {
            check(resourceId !in resources) { "Component Host resource is already registered: $resourceId." }
            resources[resourceId] = binding
            return this
        }

        /** Removes one pending registration and reports whether it existed. */
        fun unregister(functionId: WasmlineComponentFunctionId): Boolean = adapters.remove(functionId) != null

        /** Builds a detached immutable registry snapshot. */
        fun build(): WasmlineComponentHostRegistry = WasmlineComponentHostRegistry(adapters.toMap(), resources.toMap())
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}

/** Identifies one resource type imported through a Component interface. */
data class WasmlineComponentResourceId(val interfaceId: WasmlineComponentInterfaceId, val resourceName: String) {
    init {
        requireComponentIdentifier(resourceName, "Component resource identifier")
    }
}

/** Dispatches methods for Host objects backing an imported resource. */
class WasmlineComponentHostResourceBinding(
    val methods: Map<String, (Any, List<WasmlineComponentValue>) -> WasmlineCallResult<List<WasmlineComponentValue>>>,
    val drop: (Any) -> Unit = {},
) {
    init {
        require(methods.keys.none(String::isBlank)) { "Component Host resource method names must not be blank." }
    }
}
