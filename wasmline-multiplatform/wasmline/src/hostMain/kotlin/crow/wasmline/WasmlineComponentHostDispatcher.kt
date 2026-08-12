/**
 * Dispatches typed Component Model host imports to an immutable registry.
 *
 * Date: 2026-08-07
 * Author: crowforkotlin
 */
package crow.wasmline

import crow.wasmline.invocation.WasmlineCallError
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineInvocationException

/**
 * JVM-callable typed Component host dispatcher.
 *
 * Native bridges pass exact Component identifiers separately and exchange only
 * typed-value frames with this object. A null return is reserved for a missing
 * adapter so native code can preserve the canonical missing-adapter error.
 */
internal class WasmlineComponentHostDispatcher(private val registry: WasmlineComponentHostRegistry) {
    private val lock = WasmlineHostServiceLock()
    private val hostResources = linkedMapOf<UInt, HostResourceEntry>()
    private var nextRepresentation = 1u

    fun createResource(resourceId: WasmlineComponentResourceId, implementation: Any): UInt = lock.withLock {
        check(resourceId in registry.resources) { "Component Host resource type is not registered: $resourceId." }
        val representation = nextRepresentation++
        check(representation != 0u) { "Component Host resource representation space is exhausted." }
        hostResources[representation] = HostResourceEntry(resourceId, implementation)
        representation
    }

    fun bindResourceReference(representation: UInt, reference: WasmlineComponentValue.ResourceValue) = lock.withLock {
        val entry = hostResources[representation] ?: error("Component Host resource representation is not registered.")
        check(entry.reference == null) { "Component Host resource representation is already bound." }
        check(reference.origin == WasmlineComponentResourceOrigin.HOST) { "Host resource carrier must have HOST origin." }
        hostResources[representation] = entry.copy(reference = reference)
    }

    fun discardResource(representation: UInt) {
        lock.withLock { hostResources.remove(representation) }
    }

    fun releaseResources(): Int {
        val snapshot = lock.withLock {
            hostResources.values.toList().also { hostResources.clear() }
        }
        snapshot.forEach(::dropResourceEntryIgnoringFailure)
        return snapshot.size
    }

    fun activeResourceCount(): Int = lock.withLock { hostResources.size }

    @Suppress("unused")
    fun dispatch(interfaceName: String, functionName: String, arguments: ByteArray): ByteArray? {
        if (functionName.startsWith("[resource-drop]")) {
            val resourceName = functionName.removePrefix("[resource-drop]")
            val decoded = WasmlineTypedInvocationCodec.decodeComponentArguments(arguments)
            val representation = (decoded as? WasmlineCallResult.Success)?.value?.singleOrNull()
                ?.let { it as? WasmlineComponentValue.U32 }?.value
                ?: return encode(
                    WasmlineCallResult.Failure(
                        WasmlineCallError(WasmlineErrorCode.COMPONENT_RESOURCE_INVALID, "Host resource drop representation is invalid."),
                    ),
                )
            val removed = lock.withLock { hostResources.remove(representation) }
            if (removed == null || removed.id.interfaceId.value != interfaceName || removed.id.resourceName != resourceName) {
                if (removed != null) lock.withLock { hostResources[representation] = removed }
                return encode(
                    WasmlineCallResult.Failure(
                        WasmlineCallError(WasmlineErrorCode.COMPONENT_RESOURCE_INVALID, "Host resource is stale or has the wrong type."),
                    ),
                )
            }
            return encode(dropResourceEntry(removed))
        }
        val functionId = try {
            WasmlineComponentFunctionId.of(WasmlineComponentInterfaceId.of(interfaceName), functionName)
        } catch (error: IllegalArgumentException) {
            return encode(
                WasmlineCallResult.Failure(
                    WasmlineCallError(
                        code = WasmlineErrorCode.HANDLER_FAILED,
                        message = error.message ?: "Component host identifiers are invalid.",
                    ),
                ),
            )
        }
        val adapter = registry.lookup(functionId)
        val result = when (val decoded = WasmlineTypedInvocationCodec.decodeComponentArguments(arguments)) {
            is WasmlineCallResult.Failure -> decoded

            is WasmlineCallResult.Success -> if (adapter != null) {
                invokeAdapter(adapter, decoded.value)
            } else {
                invokeResourceMethod(interfaceName, functionName, decoded.value) ?: return null
            }
        }
        return encode(result)
    }

    private fun invokeResourceMethod(
        interfaceName: String,
        functionName: String,
        arguments: List<WasmlineComponentValue>,
    ): WasmlineCallResult<List<WasmlineComponentValue>>? {
        if (!functionName.startsWith("[method]")) return null
        val qualified = functionName.removePrefix("[method]")
        val resourceName = qualified.substringBefore('.', missingDelimiterValue = "")
        val methodName = qualified.substringAfter('.', missingDelimiterValue = "")
        if (resourceName.isEmpty() || methodName.isEmpty()) return null
        val reference = arguments.firstOrNull() as? WasmlineComponentValue.ResourceValue ?: return null
        val entry = lock.withLock {
            hostResources.values.firstOrNull {
                it.id.interfaceId.value == interfaceName &&
                    it.id.resourceName == resourceName &&
                    it.reference?.sameIdentity(reference) == true
            }
        } ?: return WasmlineCallResult.Failure(
            WasmlineCallError(WasmlineErrorCode.COMPONENT_RESOURCE_INVALID, "Host resource is stale or has the wrong type."),
        )
        val binding = registry.resources.getValue(entry.id)
        val method = binding.methods[methodName] ?: return null
        return try {
            method(entry.implementation, arguments.drop(1))
        } catch (error: Exception) {
            WasmlineCallResult.Failure(
                WasmlineCallError(WasmlineErrorCode.HANDLER_FAILED, error.message ?: "Host resource method failed."),
            )
        }
    }

    private data class HostResourceEntry(
        val id: WasmlineComponentResourceId,
        val implementation: Any,
        val reference: WasmlineComponentValue.ResourceValue? = null,
    )

    private fun WasmlineComponentValue.ResourceValue.sameIdentity(other: WasmlineComponentValue.ResourceValue): Boolean =
        instanceKey == other.instanceKey &&
            typeId == other.typeId &&
            handleId == other.handleId &&
            generation == other.generation &&
            origin == other.origin

    private fun dropResourceEntry(entry: HostResourceEntry): WasmlineCallResult<List<WasmlineComponentValue>> = try {
        registry.resources.getValue(entry.id).drop(entry.implementation)
        WasmlineCallResult.Success(emptyList())
    } catch (error: Exception) {
        WasmlineCallResult.Failure(
            WasmlineCallError(WasmlineErrorCode.HANDLER_FAILED, error.message ?: "Host resource drop failed."),
        )
    }

    private fun dropResourceEntryIgnoringFailure(entry: HostResourceEntry) {
        try {
            registry.resources.getValue(entry.id).drop(entry.implementation)
        } catch (_: Exception) {
            // Instance teardown must continue after user cleanup failures.
        }
    }

    private fun invokeAdapter(
        adapter: WasmlineComponentHostAdapter,
        arguments: List<WasmlineComponentValue>,
    ): WasmlineCallResult<List<WasmlineComponentValue>> = try {
        adapter.invoke(arguments)
    } catch (error: Exception) {
        WasmlineCallResult.Failure(
            WasmlineCallError(
                code = WasmlineErrorCode.HANDLER_FAILED,
                message = error.message ?: "Typed Component host adapter failed.",
            ),
        )
    }

    private fun encode(result: WasmlineCallResult<List<WasmlineComponentValue>>): ByteArray =
        when (val encoded = WasmlineTypedInvocationCodec.encodeComponentResult(result)) {
            is WasmlineCallResult.Success -> encoded.value
            is WasmlineCallResult.Failure -> throw WasmlineInvocationException(encoded.error)
        }
}
