package crow.wasmline.internal.component

import crow.wasmline.Wasmline
import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineComponentCallResult
import crow.wasmline.WasmlineComponentContract
import crow.wasmline.WasmlineComponentHostRegistry
import crow.wasmline.WasmlineComponentInstance
import crow.wasmline.WasmlineComponentModule
import crow.wasmline.WasmlineComponentResourceId
import crow.wasmline.WasmlineComponentValue
import crow.wasmline.internal.invocation.WasmlineTypedInvocationCodec
import crow.wasmline.internal.runtime.WasmlineRuntimeLock
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineFailure

/**
 * Immutable configuration for one typed Component instance.
 *
 * Date: 2026-09-02
 * Author: crowforkotlin
 */
internal data class WasmlineComponentInstanceConfiguration(
    val contract: WasmlineComponentContract?,
    val registry: WasmlineComponentHostRegistry,
)

/**
 * Owns typed Component instances for one loaded artifact.
 *
 * Date: 2026-09-02
 * Author: crowforkotlin
 */
internal class WasmlineComponentModuleState(private val owner: Wasmline) {
    private val lock = WasmlineRuntimeLock()
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

/**
 * Allocates process-local identifiers for typed Component instances.
 *
 * Date: 2026-09-02
 * Author: crowforkotlin
 */
private object WasmlineComponentInstanceIds {
    private val lock = WasmlineRuntimeLock()
    private var next = 0UL

    fun next(): ULong = lock.withLock { next++ }
}

internal fun requireIdentifier(value: String, label: String): String {
    require(value.isNotBlank()) { "$label must not be blank." }
    require(value == value.trim()) { "$label must not have leading or trailing whitespace." }
    require(value.none(Char::isWhitespace)) { "$label must not contain whitespace." }
    return value
}

internal fun componentFailure(code: WasmlineErrorCode, message: String): WasmlineCallResult.Failure =
    WasmlineCallResult.Failure(WasmlineFailure(code = code, message = message))
