@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("unused")

package crow.wasmline

import crow.wasmline.internal.WasmlineComponentBindings
import crow.wasmline.internal.bridge.WasmlineHostDispatcher
import crow.wasmline.internal.protocol.WasmlineResponseCodec
import crow.wasmline.invocation.WasmlineCallError
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.native.c.*
import kotlinx.cinterop.*
import crow.wasmline.extensions.ensureNativeRuntimeLoaded as ensureLinkedNativeRuntimeLoaded

/**
 * Kotlin/Native host handle backed by the portable Wasmline C ABI.
 *
 * Author: crowforkotlin
 * Date: 2026-08-19
 */
actual class Wasmline internal actual constructor(
    private val moduleKey: String,
    actual val config: WasmlineConfig,
    actual val descriptor: WasmlineArtifactDescriptor,
) {
    internal actual val hostServiceRegistry: WasmlineHostServiceRegistry = WasmlineHostServiceRegistry()
    internal actual val componentModuleState: WasmlineComponentModuleState = WasmlineComponentModuleState(this)

    actual fun bindComponentHost(registry: WasmlineComponentHostRegistry): Wasmline = WasmlineComponentBindings.bindHost(this, registry)

    actual fun bindComponentService(handler: (action: String, payload: ByteArray) -> WasmlineCallResult<ByteArray>): Wasmline =
        WasmlineComponentBindings.bindService(this, handler)

    internal actual fun setOutbound(dispatcher: WasmlineHostDispatcher) {
        WasmlineCallbackRegistry.register(moduleKey, dispatcher)
        wasmline_set_outbound_handler(moduleKey, config.serialization.factoryId, staticCFunction(::nativeStaticOutboundCallback))
    }

    internal actual fun setComponentHostDispatcher(dispatcher: WasmlineComponentHostDispatcher) {
        WasmlineComponentHostCallbackRegistry.register(moduleKey, dispatcher)
        if (!wasmline_set_component_host_handler(moduleKey, staticCFunction(::nativeStaticComponentHostCallback))) {
            WasmlineComponentHostCallbackRegistry.unregister(moduleKey)
            throw IllegalStateException("Failed to install the typed Component host dispatcher for '$moduleKey'.")
        }
    }

    internal actual fun call(action: String, inputBytes: ByteArray): ByteArray = memScoped {
        val keyCstr = moduleKey
        val actionCstr = action
        val dataSize = inputBytes.size.toULong()

        val outLen = alloc<ULongVar>()

        inputBytes.usePinned { pinned ->
            val dataPtr = if (inputBytes.isNotEmpty()) pinned.addressOf(0) else null
            val resultPtr = wasmline_invoke_inbound(
                keyCstr,
                actionCstr,
                action.length.toULong(),
                dataPtr,
                dataSize,
                outLen.ptr,
            )

            if (resultPtr == null) {
                return@memScoped byteArrayOf()
            }

            if (outLen.value > Int.MAX_VALUE.toULong()) {
                wasmline_free_memory(resultPtr)
                return@memScoped byteArrayOf()
            }
            val length = outLen.value.toInt()
            if (length == 0) {
                wasmline_free_memory(resultPtr)
                return@memScoped byteArrayOf()
            }

            val resultArray = resultPtr.readBytes(length)
            wasmline_free_memory(resultPtr)
            resultArray
        }
    }

    internal actual fun invokeRawCarrier(exportName: String, arguments: ByteArray): WasmlineCallResult<ByteArray> =
        invokeTypedCarrier(arguments) { dataPtr, dataSize, outLen ->
            wasmline_invoke_raw(moduleKey, exportName, exportName.length.toULong(), dataPtr, dataSize, outLen)
        }

    internal actual fun invokeComponentCarrier(exportName: String, arguments: ByteArray): WasmlineCallResult<ByteArray> =
        invokeTypedCarrier(arguments) { dataPtr, dataSize, outLen ->
            wasmline_invoke_component(moduleKey, exportName, exportName.length.toULong(), dataPtr, dataSize, outLen)
        }

    internal actual fun instantiateComponentInstance(instanceKey: String, dispatcher: WasmlineComponentHostDispatcher): Boolean {
        WasmlineComponentHostCallbackRegistry.register(instanceKey, dispatcher)
        if (wasmline_instantiate_component(moduleKey, instanceKey, staticCFunction(::nativeStaticComponentHostCallback))) return true
        WasmlineComponentHostCallbackRegistry.unregister(instanceKey)
        return false
    }

    internal actual fun invokeComponentInstanceCarrier(
        instanceKey: String,
        exportName: String,
        arguments: ByteArray,
    ): WasmlineCallResult<ByteArray> = invokeTypedCarrier(arguments) { dataPtr, dataSize, outLen ->
        wasmline_invoke_component_instance(
            instanceKey,
            exportName,
            exportName.length.toULong(),
            dataPtr,
            dataSize,
            outLen,
        )
    }

    internal actual fun releaseComponentInstance(instanceKey: String) {
        WasmlineComponentHostCallbackRegistry.unregister(instanceKey)
        wasmline_release_component_instance(instanceKey)
    }

    internal actual fun dropComponentResource(instanceKey: String, reference: WasmlineComponentValue.ResourceValue): Boolean {
        val encoded = WasmlineTypedInvocationCodec.encodeComponentArguments(listOf(reference))
        return encoded is WasmlineCallResult.Success &&
            encoded.value.usePinned { pinned ->
                wasmline_drop_component_resource(
                    instanceKey,
                    if (encoded.value.isEmpty()) null else pinned.addressOf(0),
                    encoded.value.size.toULong(),
                )
            }
    }

    internal actual fun createComponentHostResource(
        instanceKey: String,
        interfaceId: String,
        resourceName: String,
        representation: UInt,
    ): WasmlineCallResult<WasmlineComponentValue.ResourceValue> = memScoped {
        val outLen = alloc<ULongVar>()
        val pointer = wasmline_create_component_host_resource(instanceKey, interfaceId, resourceName, representation, outLen.ptr)
            ?: return@memScoped WasmlineCallResult.Failure(
                WasmlineCallError(WasmlineErrorCode.COMPONENT_RESOURCE_INVALID, "Native Host resource creation failed."),
            )
        if (outLen.value > Int.MAX_VALUE.toULong()) {
            wasmline_free_memory(pointer)
            return@memScoped WasmlineCallResult.Failure(
                WasmlineCallError(WasmlineErrorCode.COMPONENT_RESOURCE_INVALID, "Native Host resource carrier is too large."),
            )
        }
        val encoded = pointer.readBytes(outLen.value.toInt())
        wasmline_free_memory(pointer)
        when (val decoded = WasmlineTypedInvocationCodec.decodeComponentArguments(encoded)) {
            is WasmlineCallResult.Failure -> decoded

            is WasmlineCallResult.Success -> {
                val resource = decoded.value.singleOrNull() as? WasmlineComponentValue.ResourceValue
                if (resource != null) {
                    WasmlineCallResult.Success(resource)
                } else {
                    WasmlineCallResult.Failure(
                        WasmlineCallError(WasmlineErrorCode.COMPONENT_RESOURCE_INVALID, "Native Host resource carrier is invalid."),
                    )
                }
            }
        }
    }

    actual fun close() {
        componentModuleState.close()
        hostServiceRegistry.clear()
        WasmlineCallbackRegistry.unregister(moduleKey)
        WasmlineComponentHostCallbackRegistry.unregister(moduleKey)
        wasmline_release_module(moduleKey)
    }
}

private fun invokeTypedCarrier(
    inputBytes: ByteArray,
    invoke: (CPointer<ByteVar>?, ULong, CPointer<ULongVar>) -> CPointer<ByteVar>?,
): WasmlineCallResult<ByteArray> = memScoped {
    val outLen = alloc<ULongVar>()
    val resultPtr = inputBytes.usePinned { pinned ->
        val dataPtr = if (inputBytes.isEmpty()) null else pinned.addressOf(0)
        invoke(dataPtr, inputBytes.size.toULong(), outLen.ptr)
    }
    if (resultPtr == null) {
        return@memScoped WasmlineCallResult.Failure(
            WasmlineCallError(WasmlineErrorCode.TRANSPORT_FAILURE, "Native typed invocation returned no response."),
        )
    }
    if (outLen.value > Int.MAX_VALUE.toULong()) {
        wasmline_free_memory(resultPtr)
        return@memScoped WasmlineCallResult.Failure(
            WasmlineCallError(WasmlineErrorCode.TRANSPORT_FAILURE, "Native typed invocation response is too large."),
        )
    }
    val result = resultPtr.readBytes(outLen.value.toInt())
    wasmline_free_memory(resultPtr)
    WasmlineCallResult.Success(result)
}

/**
 * Serializes Native callback registry access through the bridge's recursive lock.
 *
 * Author: crowforkotlin
 * Date: 2026-08-19
 */
internal actual class WasmlineHostServiceLock {
    actual fun <T> withLock(block: () -> T): T {
        wasmline_lock()
        return try {
            block()
        } finally {
            wasmline_unlock()
        }
    }
}

private fun ensureNativeRuntimeLoaded() {
    ensureLinkedNativeRuntimeLoaded()
    wasmline_native_engine_link_anchor()
}

internal actual fun platformWasmlinePreload() {
    ensureNativeRuntimeLoaded()
}

internal actual fun platformWasmlineShutdown() {
    ensureNativeRuntimeLoaded()
    wasmline_release_engine()
}

internal actual fun platformWasmlineWarmUp(engine: WasmlineEngineKind) {
    ensureNativeRuntimeLoaded()
    val usePulley = engine == WasmlineEngineKind.PULLEY
    val supported = if (usePulley) wasmline_supports_pulley() else wasmline_supports_cranelift()
    require(supported) {
        "The linked Wasmline runtime does not support the $engine engine."
    }
    check(wasmline_warmup_engine(usePulley)) {
        "Cannot select the $engine engine while artifacts for another engine are still loaded."
    }
}

internal actual fun platformWasmlineRuntimeCapabilities(): WasmlineRuntimeCapabilities {
    ensureNativeRuntimeLoaded()
    return WasmlineRuntimeCapabilities(
        wasmtimeVersion = requireNotNull(wasmline_wasmtime_version()).toKString(),
        supportsCranelift = wasmline_supports_cranelift(),
        supportsPulley = wasmline_supports_pulley(),
        targetOs = requireNotNull(wasmline_target_os()).toKString(),
        targetCpu = requireNotNull(wasmline_target_cpu()).toKString(),
        is64Bit = wasmline_target_is_64_bit(),
    )
}

internal actual fun platformWasmlineNativeRuntimeInfo(): WasmlineNativeRuntimeInfo? =
    platformWasmlineRuntimeCapabilities().nativeRuntimeInfo

internal actual fun platformWasmlineLoadArtifact(filepath: String, config: WasmlineConfig): WasmlineLoadState =
    platformWasmlineLoadArtifact(WasmlineArtifactDescriptor(path = filepath), config)

internal actual fun platformWasmlineLoadArtifact(descriptor: WasmlineArtifactDescriptor, config: WasmlineConfig): WasmlineLoadState {
    val isUnsafe = !config.supportConcurrent
    return WasmlineLocalArtifactBridge.load(
        descriptor = descriptor,
        config = config,
        platform = object : WasmlinePlatformArtifactBridge {
            override fun createWasmline(moduleKey: String, config: WasmlineConfig, descriptor: WasmlineArtifactDescriptor): Wasmline =
                Wasmline(moduleKey, config, descriptor)

            override fun resolveArtifact(path: String): ResolvedPrecompiledArtifact? {
                if (!wasmline_path_exists(path)) return null
                return ResolvedPrecompiledArtifact(
                    artifactPath = path,
                    moduleKey = path,
                )
            }

            override fun validationError(descriptor: WasmlineArtifactDescriptor): String? =
                descriptor.runtimeCompatibilityError(platformWasmlineRuntimeCapabilities())

            override fun requiresExplicitArtifactFormat(): Boolean = true

            override fun loadPrecompiled(moduleKey: String, path: String, descriptor: WasmlineArtifactDescriptor): Boolean {
                ensureNativeRuntimeLoaded()
                val formatCode = descriptor.artifactFormat?.nativeBridgeCode() ?: return false
                return when (descriptor.executionModel) {
                    WasmlineExecutionModel.CORE_WASM ->
                        wasmline_load_module_with_format(moduleKey, path, formatCode, isUnsafe)

                    WasmlineExecutionModel.COMPONENT_MODEL ->
                        wasmline_load_component_with_format(moduleKey, path, formatCode, isUnsafe)
                }
            }

            override fun loadFailureMessage(descriptor: WasmlineArtifactDescriptor): String =
                "[Wasmline] Native artifact load failed for: ${descriptor.path}"
        },
    )
}

private object WasmlineCallbackRegistry {
    private val dispatchers = mutableMapOf<String, WasmlineHostDispatcher>()
    private val lock = WasmlineHostServiceLock()

    fun register(key: String, dispatcher: WasmlineHostDispatcher) {
        lock.withLock {
            dispatchers[key] = dispatcher
        }
    }

    fun unregister(key: String) {
        lock.withLock {
            dispatchers.remove(key)
        }
    }

    fun find(key: String): WasmlineHostDispatcher? = lock.withLock { dispatchers[key] }
}

/**
 * Stores typed Component host dispatchers for Native artifact and instance keys.
 *
 * Author: crowforkotlin
 * Date: 2026-08-19
 */
internal object WasmlineComponentHostCallbackRegistry {
    private val dispatchers = mutableMapOf<String, WasmlineComponentHostDispatcher>()
    private val lock = WasmlineHostServiceLock()

    fun register(key: String, dispatcher: WasmlineComponentHostDispatcher) {
        lock.withLock {
            dispatchers[key] = dispatcher
        }
    }

    fun unregister(key: String) {
        lock.withLock {
            dispatchers.remove(key)
        }
    }

    fun find(key: String): WasmlineComponentHostDispatcher? = lock.withLock { dispatchers[key] }
}

private fun callbackLength(value: ULong): Int? = if (value > Int.MAX_VALUE.toULong()) null else value.toInt()

/**
 * Dispatches an outbound Native callback without allowing exceptions across C.
 *
 * Author: crowforkotlin
 * Date: 2026-08-19
 */
internal fun nativeStaticOutboundCallback(
    key: CPointer<ByteVar>?,
    keyLen: ULong,
    action: CPointer<ByteVar>?,
    actionLen: ULong,
    payload: CPointer<ByteVar>?,
    payloadLen: ULong,
    outLen: CPointer<ULongVar>?,
): CPointer<ByteVar>? {
    val keySize = callbackLength(keyLen)
    val actionSize = callbackLength(actionLen)
    val payloadSize = callbackLength(payloadLen)
    val response = if (keySize == null || actionSize == null || payloadSize == null) {
        WasmlineResponseCodec.encodeFailure(
            WasmlineCallError(
                code = WasmlineErrorCode.TRANSPORT_FAILURE,
                message = "Native outbound callback received an oversized buffer.",
            ),
        )
    } else if ((keySize > 0 && key == null) || (actionSize > 0 && action == null) || (payloadSize > 0 && payload == null)) {
        WasmlineResponseCodec.encodeFailure(
            WasmlineCallError(
                code = WasmlineErrorCode.TRANSPORT_FAILURE,
                message = "Native outbound callback received a null buffer.",
            ),
        )
    } else {
        val keyStr = key?.readBytes(keySize)?.decodeToString() ?: ""
        val actionStr = action?.readBytes(actionSize)?.decodeToString() ?: ""
        val payloadBytes = payload?.readBytes(payloadSize) ?: byteArrayOf()
        val dispatcher = WasmlineCallbackRegistry.find(keyStr)
        try {
            if (dispatcher == null) {
                WasmlineResponseCodec.encodeFailure(
                    WasmlineCallError(
                        code = WasmlineErrorCode.ACTION_NOT_BOUND,
                        message = "No Wasmline outbound action is bound.",
                    ),
                )
            } else {
                dispatcher.dispatch(actionStr, payloadBytes)
            }
        } catch (error: Throwable) {
            WasmlineResponseCodec.encodeFailure(
                WasmlineCallError(
                    code = WasmlineErrorCode.HANDLER_FAILED,
                    message = error.message ?: "Wasmline outbound action handler failed.",
                ),
            )
        }
    }

    outLen?.pointed?.value = response.size.toULong()
    if (response.isEmpty()) return null

    val result = wasmline_allocate_memory(response.size.toULong()) ?: return null
    response.forEachIndexed { index, value -> result[index] = value }
    return result
}

private fun encodeComponentHostFailure(code: WasmlineErrorCode, message: String): ByteArray = when (
    val encoded = WasmlineTypedInvocationCodec.encodeComponentResult(
        WasmlineCallResult.Failure(WasmlineCallError(code, message)),
    )
) {
    is WasmlineCallResult.Success -> encoded.value
    is WasmlineCallResult.Failure -> byteArrayOf()
}

/**
 * Dispatches a typed Component host import without allowing exceptions across C.
 *
 * Author: crowforkotlin
 * Date: 2026-08-19
 */
internal fun nativeStaticComponentHostCallback(
    key: CPointer<ByteVar>?,
    keyLen: ULong,
    interfaceName: CPointer<ByteVar>?,
    interfaceNameLen: ULong,
    functionName: CPointer<ByteVar>?,
    functionNameLen: ULong,
    arguments: CPointer<ByteVar>?,
    argumentsLen: ULong,
    outLen: CPointer<ULongVar>?,
): CPointer<ByteVar>? {
    val keySize = callbackLength(keyLen)
    val interfaceSize = callbackLength(interfaceNameLen)
    val functionSize = callbackLength(functionNameLen)
    val argumentsSize = callbackLength(argumentsLen)
    val response: ByteArray? = if (keySize == null || interfaceSize == null || functionSize == null || argumentsSize == null) {
        encodeComponentHostFailure(
            WasmlineErrorCode.TRANSPORT_FAILURE,
            "Native typed Component callback received an oversized buffer.",
        )
    } else if ((keySize > 0 && key == null) ||
        (interfaceSize > 0 && interfaceName == null) ||
        (functionSize > 0 && functionName == null) ||
        (argumentsSize > 0 && arguments == null)
    ) {
        encodeComponentHostFailure(
            WasmlineErrorCode.TRANSPORT_FAILURE,
            "Native typed Component callback received a null buffer.",
        )
    } else {
        val keyString = key?.readBytes(keySize)?.decodeToString() ?: ""
        val interfaceString = interfaceName?.readBytes(interfaceSize)?.decodeToString() ?: ""
        val functionString = functionName?.readBytes(functionSize)?.decodeToString() ?: ""
        val argumentBytes = arguments?.readBytes(argumentsSize) ?: byteArrayOf()
        val dispatcher = WasmlineComponentHostCallbackRegistry.find(keyString)
        try {
            if (dispatcher == null) {
                encodeComponentHostFailure(
                    WasmlineErrorCode.ACTION_NOT_BOUND,
                    "No typed Component host adapter is bound.",
                )
            } else {
                dispatcher.dispatch(interfaceString, functionString, argumentBytes)
            }
        } catch (error: Throwable) {
            encodeComponentHostFailure(
                WasmlineErrorCode.HANDLER_FAILED,
                error.message ?: "Native typed Component host adapter failed.",
            )
        }
    }

    if (response == null) {
        outLen?.pointed?.value = 0uL
        return null
    }
    outLen?.pointed?.value = response.size.toULong()
    if (response.isEmpty()) return null

    val result = wasmline_allocate_memory(response.size.toULong()) ?: return null
    response.forEachIndexed { index, value -> result[index] = value }
    return result
}
