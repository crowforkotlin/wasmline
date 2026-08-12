package crow.wasmline

import crow.wasmline.invocation.WasmlineCallError
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode

/** Identifies the exact WIT package/world consumed by a generated Host binding. */
data class WasmlineComponentContract(val packageId: String, val world: String, val witSha256: String) {
    init {
        requireIdentifier(packageId, "WIT package id")
        requireIdentifier(world, "WIT world")
        require(SHA_256.matches(witSha256)) { "WIT SHA-256 must contain 64 lowercase hexadecimal characters." }
    }

    internal fun requireMatches(descriptor: WasmlineArtifactDescriptor) {
        val metadata = descriptor.contractMetadata
        requireMetadata(metadata, WasmlineTypedComponentContract.METADATA_WIT_PACKAGE, packageId)
        requireMetadata(metadata, WasmlineTypedComponentContract.METADATA_WORLD, world)
        requireMetadata(metadata, WasmlineTypedComponentContract.METADATA_WIT_SHA256, witSha256)
    }

    private fun requireMetadata(metadata: Map<String, String>, key: String, expected: String) {
        val actual = metadata[key]
        check(actual != null) { "Typed Component artifact is missing required contract metadata '$key'." }
        check(actual == expected) {
            "Typed Component contract mismatch for '$key': expected '$expected', actual '$actual'."
        }
    }

    private companion object {
        val SHA_256 = Regex("[0-9a-f]{64}")
    }
}

/** One exported Component function with its canonical interface-qualified name. */
class WasmlineComponentExport private constructor(val interfaceId: WasmlineComponentInterfaceId?, val functionName: String) {
    val value: String = interfaceId?.let { "${it.value}#$functionName" } ?: functionName

    override fun equals(other: Any?): Boolean = other is WasmlineComponentExport && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        fun of(interfaceId: WasmlineComponentInterfaceId, functionName: String): WasmlineComponentExport = WasmlineComponentExport(
            interfaceId = interfaceId,
            functionName = requireIdentifier(functionName, "Component export function"),
        )

        fun of(interfaceId: String, functionName: String): WasmlineComponentExport =
            of(WasmlineComponentInterfaceId.of(interfaceId), functionName)

        /** Creates a token for a function exported directly by the world rather than through an interface. */
        fun root(functionName: String): WasmlineComponentExport = WasmlineComponentExport(
            interfaceId = null,
            functionName = requireIdentifier(functionName, "Component root export function"),
        )
    }
}

/** Metadata emitted for each generated Host facade function. */
data class WasmlineComponentFunctionBinding(val export: WasmlineComponentExport, val parameterCount: Int, val hasResult: Boolean) {
    init {
        require(parameterCount >= 0) { "Component parameter count must not be negative." }
    }
}

/** Value token generated from a WIT export interface. */
interface WasmlineComponentExportBinding<out Client> {
    val contract: WasmlineComponentContract
    val interfaceId: WasmlineComponentInterfaceId
    val functions: List<WasmlineComponentFunctionBinding>
    fun attach(instance: WasmlineComponentInstance): Client
}

/** Value token generated from a WIT import interface. */
interface WasmlineComponentImportBindingFactory<in Implementation : Any> {
    val contract: WasmlineComponentContract
    val interfaceId: WasmlineComponentInterfaceId
    val functions: List<WasmlineComponentFunctionId>
    fun bind(implementation: Implementation, registry: WasmlineComponentHostRegistry.Builder)
}

/** Preserves the distinction between a WIT business result and a Wasmline runtime failure. */
sealed interface WasmlineWitResult<out Success, out Error> {
    data class Ok<Success>(val value: Success) : WasmlineWitResult<Success, Nothing>
    data class Err<Error>(val error: Error) : WasmlineWitResult<Nothing, Error>
}

/** Typed Component capability over one loaded compiled artifact. */
class WasmlineComponentModule internal constructor(private val state: WasmlineComponentModuleState) {
    val descriptor: WasmlineArtifactDescriptor
        get() = state.descriptor

    fun instantiate(configure: WasmlineComponentInstanceBuilder.() -> Unit = {}): WasmlineComponentInstance {
        val builder = WasmlineComponentInstanceBuilder(descriptor).apply(configure)
        return state.instantiate(builder.build())
    }
}

/** Builds the immutable import registry used by one Component instance. */
class WasmlineComponentInstanceBuilder internal constructor(private val descriptor: WasmlineArtifactDescriptor) {
    private val registry = WasmlineComponentHostRegistry.builder()
    private var requiredContract: WasmlineComponentContract? = null

    fun requireContract(contract: WasmlineComponentContract) {
        requiredContract?.let { check(it == contract) { "A Component instance cannot require two different WIT worlds." } }
        contract.requireMatches(descriptor)
        requiredContract = contract
    }

    fun bindImports(hostRegistry: WasmlineComponentHostRegistry) {
        registry.registerAll(hostRegistry)
    }

    fun <Implementation : Any> bind(binding: WasmlineComponentImportBindingFactory<Implementation>, implementation: Implementation) {
        requireContract(binding.contract)
        binding.bind(implementation, registry)
    }

    internal fun build(): WasmlineComponentInstanceConfiguration = WasmlineComponentInstanceConfiguration(
        contract = requiredContract,
        registry = registry.build(),
    )
}

internal data class WasmlineComponentInstanceConfiguration(
    val contract: WasmlineComponentContract?,
    val registry: WasmlineComponentHostRegistry,
)

/** One isolated Store/Linker/Component instance with immutable imports. */
class WasmlineComponentInstance internal constructor(
    private val state: WasmlineComponentModuleState,
    internal val instanceKey: String,
    private val contract: WasmlineComponentContract?,
    private val dispatcher: WasmlineComponentHostDispatcher,
) {
    private val lock = WasmlineHostServiceLock()
    private var closed = false

    val descriptor: WasmlineArtifactDescriptor
        get() = state.descriptor

    fun invoke(
        export: WasmlineComponentExport,
        arguments: List<WasmlineComponentValue> = emptyList(),
    ): WasmlineCallResult<WasmlineComponentCallResult> = lock.withLock {
        if (closed) {
            return@withLock componentFailure(
                WasmlineErrorCode.ENGINE_NOT_INITIALIZED,
                "Component instance is closed.",
            )
        }
        state.invoke(instanceKey, export.value, arguments)
    }

    fun <Client> link(binding: WasmlineComponentExportBinding<Client>): Client = lock.withLock {
        check(!closed) { "Cannot link a generated binding after the Component instance was closed." }
        binding.contract.requireMatches(descriptor)
        contract?.let {
            check(it == binding.contract) { "Generated binding contract does not match the instantiated Component contract." }
        }
        check(binding.functions.isNotEmpty()) { "Generated Component export binding declares no functions." }
        binding.attach(this)
    }

    internal fun dropResource(reference: WasmlineComponentValue.ResourceValue): Boolean = lock.withLock {
        check(!closed) { "Cannot drop a Component resource after the instance was closed." }
        check(reference.instanceKey == instanceKey) { "Component resource belongs to a different instance." }
        check(reference.ownership == WasmlineComponentResourceOwnership.OWN) {
            "Borrowed Component resources are scoped to their invocation and cannot be closed explicitly."
        }
        state.dropResource(instanceKey, reference)
    }

    internal fun requireResourceAccess(reference: WasmlineComponentValue.ResourceValue) = lock.withLock {
        check(!closed) { "Cannot access a Component resource after the instance was closed." }
        check(reference.instanceKey == instanceKey) { "Component resource belongs to a different instance." }
    }

    fun createHostResource(resourceId: WasmlineComponentResourceId, implementation: Any): WasmlineComponentValue.ResourceValue =
        lock.withLock {
            check(!closed) { "Cannot create a Host resource after the Component instance was closed." }
            val representation = dispatcher.createResource(resourceId, implementation)
            when (
                val created = state.createHostResource(instanceKey, resourceId, representation)
            ) {
                is WasmlineCallResult.Success -> created.value.also {
                    dispatcher.bindResourceReference(representation, it)
                }

                is WasmlineCallResult.Failure -> {
                    dispatcher.discardResource(representation)
                    throw crow.wasmline.invocation.WasmlineInvocationException(created.error)
                }
            }
        }

    /** Snapshot of Host-defined resources retained by this instance. */
    fun resourceDiagnostics(): WasmlineComponentResourceDiagnostics =
        WasmlineComponentResourceDiagnostics(activeHostResources = dispatcher.activeResourceCount())

    fun close() {
        val shouldClose = lock.withLock {
            if (closed) {
                false
            } else {
                closed = true
                true
            }
        }
        if (shouldClose) {
            try {
                state.release(instanceKey)
            } finally {
                dispatcher.releaseResources()
            }
        }
    }
}

/** Testable resource-table snapshot for one live Component instance. */
data class WasmlineComponentResourceDiagnostics(val activeHostResources: Int)

/**
 * Host-side lifetime wrapper for a Component `own<T>` value.
 *
 * The wrapper carries no ABI logic: its reference is validated and dropped by
 * the owning Wasmline Component instance. A borrowed value must be represented
 * by a call-scoped adapter and must not be wrapped as this type.
 */
abstract class WasmlineComponentResource protected constructor(
    protected val instance: WasmlineComponentInstance,
    reference: WasmlineComponentValue.ResourceValue,
) : AutoCloseable {
    private var currentReference: WasmlineComponentValue.ResourceValue? = reference

    val isClosed: Boolean
        get() = currentReference == null

    protected val reference: WasmlineComponentValue.ResourceValue
        get() = currentReference
            ?.also(instance::requireResourceAccess)
            ?: error("Component resource is already closed.")

    final override fun close() {
        val value = currentReference ?: return
        check(value.ownership == WasmlineComponentResourceOwnership.OWN) {
            "Borrowed Component resources cannot be closed explicitly."
        }
        check(instance.dropResource(value)) { "Component resource drop failed or the resource is stale." }
        currentReference = null
    }

    /** Returns the opaque carrier accepted by generated WIT codecs. */
    fun toComponentValue(): WasmlineComponentValue.ResourceValue = reference

    /** Consumes this `own<T>` wrapper when ownership is transferred into a Component call. */
    fun transferToComponent(): WasmlineComponentValue.ResourceValue {
        val value = reference
        check(value.ownership == WasmlineComponentResourceOwnership.OWN) {
            "Only owned Component resources can transfer ownership."
        }
        currentReference = null
        return value
    }
}

/** Base class for generated wrappers around a guest-exported resource. */
abstract class WasmlineGuestComponentResource protected constructor(
    instance: WasmlineComponentInstance,
    reference: WasmlineComponentValue.ResourceValue,
) : WasmlineComponentResource(instance, reference)

/** Base class for generated wrappers around a Host-defined imported resource. */
abstract class WasmlineHostComponentResource protected constructor(
    instance: WasmlineComponentInstance,
    reference: WasmlineComponentValue.ResourceValue,
) : WasmlineComponentResource(instance, reference)

inline fun <Result> WasmlineComponentInstance.use(block: (WasmlineComponentInstance) -> Result): Result = try {
    block(this)
} finally {
    close()
}

/** Requests the typed WIT capability from the unified loaded artifact handle. */
fun Wasmline.component(): WasmlineComponentModule {
    require(descriptor.executionModel == WasmlineExecutionModel.COMPONENT_MODEL) {
        "Typed Component capability requires executionModel=COMPONENT_MODEL."
    }
    require(descriptor.invocationProtocol == WasmlineInvocationProtocol.COMPONENT_EXPORT) {
        "Typed Component capability requires invocationProtocol=COMPONENT_EXPORT, actual ${descriptor.invocationProtocol}."
    }
    return componentModuleState.module
}

internal class WasmlineComponentModuleState(private val owner: Wasmline) {
    private val lock = WasmlineHostServiceLock()
    private val instances = linkedMapOf<String, WasmlineComponentInstance>()
    private var closed = false

    val descriptor: WasmlineArtifactDescriptor
        get() = owner.descriptor

    val module: WasmlineComponentModule = WasmlineComponentModule(this)

    fun instantiate(configuration: WasmlineComponentInstanceConfiguration): WasmlineComponentInstance = lock.withLock {
        check(!closed) { "Cannot instantiate a closed Component module." }
        configuration.contract?.requireMatches(descriptor)
        val instanceKey = "wasmline:component-instance:${WasmlineComponentInstanceIds.next()}:${descriptor.path}"
        val dispatcher = WasmlineComponentHostDispatcher(configuration.registry)
        check(owner.instantiateComponentInstance(instanceKey, dispatcher)) {
            "Failed to instantiate typed Component '$instanceKey'. Verify that all required imports are bound."
        }
        WasmlineComponentInstance(this, instanceKey, configuration.contract, dispatcher).also { instances[instanceKey] = it }
    }

    fun invoke(
        instanceKey: String,
        exportName: String,
        arguments: List<WasmlineComponentValue>,
    ): WasmlineCallResult<WasmlineComponentCallResult> {
        if (exportName.isBlank()) return componentFailure(WasmlineErrorCode.INVALID_PAYLOAD, "Export name must not be blank.")
        return when (val encoded = WasmlineTypedInvocationCodec.encodeComponentArguments(arguments)) {
            is WasmlineCallResult.Failure -> encoded

            is WasmlineCallResult.Success -> when (
                val carrier = owner.invokeComponentInstanceCarrier(instanceKey, exportName, encoded.value)
            ) {
                is WasmlineCallResult.Failure -> carrier
                is WasmlineCallResult.Success -> WasmlineTypedInvocationCodec.decodeComponentResult(carrier.value)
            }
        }
    }

    fun release(instanceKey: String) {
        val shouldRelease = lock.withLock { instances.remove(instanceKey) != null }
        if (shouldRelease) owner.releaseComponentInstance(instanceKey)
    }

    fun dropResource(instanceKey: String, reference: WasmlineComponentValue.ResourceValue): Boolean =
        owner.dropComponentResource(instanceKey, reference)

    fun createHostResource(
        instanceKey: String,
        resourceId: WasmlineComponentResourceId,
        representation: UInt,
    ): WasmlineCallResult<WasmlineComponentValue.ResourceValue> = owner.createComponentHostResource(
        instanceKey,
        resourceId.interfaceId.value,
        resourceId.resourceName,
        representation,
    )

    fun close() {
        val snapshot: List<WasmlineComponentInstance> = lock.withLock {
            if (closed) {
                emptyList()
            } else {
                closed = true
                instances.values.toList()
            }
        }
        snapshot.forEach(WasmlineComponentInstance::close)
    }
}

private object WasmlineComponentInstanceIds {
    private val lock = WasmlineHostServiceLock()
    private var next = 0UL

    fun next(): ULong = lock.withLock { next++ }
}

private fun requireIdentifier(value: String, label: String): String {
    require(value.isNotBlank()) { "$label must not be blank." }
    require(value == value.trim()) { "$label must not have leading or trailing whitespace." }
    require(value.none(Char::isWhitespace)) { "$label must not contain whitespace." }
    return value
}

private fun componentFailure(code: WasmlineErrorCode, message: String): WasmlineCallResult.Failure =
    WasmlineCallResult.Failure(WasmlineCallError(code = code, message = message))
