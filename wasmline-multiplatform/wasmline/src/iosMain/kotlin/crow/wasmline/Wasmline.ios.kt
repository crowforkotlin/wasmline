@file:OptIn(ExperimentalForeignApi::class)

package crow.wasmline

import crow.wasmline.extensions.loadNativeLibrary
import crow.wasmline.native.c.*
import crow.wasmline.internal.bridge.WasmlineHostDispatcher
import crow.wasmline.internal.protocol.WasmlineResponseCodec
import crow.wasmline.invocation.WasmlineCallError
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineCallResult
import kotlinx.cinterop.*
import platform.Foundation.NSFileManager

actual class Wasmline actual internal constructor(
    private val moduleKey: String,
    actual val config: WasmlineConfig,
    actual val descriptor: WasmlineArtifactDescriptor,
) {

    actual internal fun setOutbound(dispatcher: WasmlineHostDispatcher) {
        WasmlineCallbackRegistry.register(moduleKey, dispatcher)
        wasmline_set_outbound_handler(moduleKey, staticCFunction(::iosStaticOutboundCallback))
    }

    actual internal fun call(action: String, inputBytes: ByteArray): ByteArray = memScoped {
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

    actual internal fun invokeRawCarrier(exportName: String, arguments: ByteArray): WasmlineCallResult<ByteArray> =
        invokeTypedCarrier(arguments) { dataPtr, dataSize, outLen ->
            wasmline_invoke_raw(moduleKey, exportName, exportName.length.toULong(), dataPtr, dataSize, outLen)
        }

    actual internal fun invokeComponentCarrier(exportName: String, arguments: ByteArray): WasmlineCallResult<ByteArray> =
        invokeTypedCarrier(arguments) { dataPtr, dataSize, outLen ->
            wasmline_invoke_component(moduleKey, exportName, exportName.length.toULong(), dataPtr, dataSize, outLen)
        }

    actual fun close() {
        WasmlineCallbackRegistry.unregister(moduleKey)
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
            WasmlineCallError(WasmlineErrorCode.TRANSPORT_FAILURE, "iOS typed invocation returned no response."),
        )
    }
    if (outLen.value > Int.MAX_VALUE.toULong()) {
        wasmline_free_memory(resultPtr)
        return@memScoped WasmlineCallResult.Failure(
            WasmlineCallError(WasmlineErrorCode.TRANSPORT_FAILURE, "iOS typed invocation response is too large."),
        )
    }
    val result = resultPtr.readBytes(outLen.value.toInt())
    wasmline_free_memory(resultPtr)
    WasmlineCallResult.Success(result)
}

private fun iosBootstrap() {
    loadNativeLibrary()
}

actual fun wasmlineBootstrap() {
    iosBootstrap()
}

actual fun wasmlineShutdown() {
    iosBootstrap()
    wasmline_release_engine()
}

actual fun wasmlineWarmup(mode: WasmlineWarmupMode) {
    iosBootstrap()
    if (mode == WasmlineWarmupMode.CRANELIFT) {
        WasmlineLog.logger?.warn("[Wasmline] CRANELIFT warmup is not supported on iOS (JIT restricted). Forcing PULLEY.")
    }
    wasmline_warmup_engine(true)
}

actual fun wasmlineLoadArtifact(filepath: String, config: WasmlineConfig): WasmlineLoadState =
    wasmlineLoadArtifact(WasmlineArtifactDescriptor(path = filepath), config)

actual fun wasmlineLoadArtifact(descriptor: WasmlineArtifactDescriptor, config: WasmlineConfig): WasmlineLoadState {
    val fileManager = NSFileManager.defaultManager
    val isUnsafe = !config.supportConcurrent
    return WasmlineLocalArtifactBridge.load(
        descriptor = descriptor,
        config = config,
        platform = object : WasmlinePlatformArtifactBridge {
            override fun createWasmline(moduleKey: String, config: WasmlineConfig, descriptor: WasmlineArtifactDescriptor): Wasmline =
                Wasmline(moduleKey, config, descriptor)

            override fun resolveArtifact(path: String): ResolvedPrecompiledArtifact? {
                if (!fileManager.fileExistsAtPath(path)) return null
                return ResolvedPrecompiledArtifact(
                    artifactPath = path,
                    moduleKey = path,
                )
            }

            override fun loadPrecompiled(moduleKey: String, path: String, descriptor: WasmlineArtifactDescriptor): Boolean {
                iosBootstrap()
                return when (descriptor.executionModel) {
                    WasmlineExecutionModel.CORE_WASM -> wasmline_load_module(moduleKey, path, isUnsafe)
                    WasmlineExecutionModel.COMPONENT_MODEL -> wasmline_load_component(moduleKey, path, isUnsafe)
                }
            }

            override fun loadFailureMessage(descriptor: WasmlineArtifactDescriptor): String {
                return "[Wasmline] Native artifact load failed for: ${descriptor.path}"
            }
        },
    )
}

private object WasmlineCallbackRegistry {
    private val dispatchers = mutableMapOf<String, WasmlineHostDispatcher>()

    fun register(key: String, dispatcher: WasmlineHostDispatcher) {
        dispatchers[key] = dispatcher
    }

    fun unregister(key: String) {
        dispatchers.remove(key)
    }

    fun findAny(): WasmlineHostDispatcher? = dispatchers.values.firstOrNull()
}

internal fun iosStaticOutboundCallback(
    action: CPointer<ByteVar>?,
    actionLen: ULong,
    payload: CPointer<ByteVar>?,
    payloadLen: ULong,
    outLen: CPointer<ULongVar>?,
): CPointer<ByteVar>? {
    val actionStr = action?.toKString() ?: ""
    val payloadBytes = payload?.readBytes(payloadLen.toInt()) ?: byteArrayOf()

    val dispatcher = WasmlineCallbackRegistry.findAny()
    val response = try {
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

    outLen?.pointed?.value = response.size.toULong()
    if (response.isEmpty()) return null

    val result = nativeHeap.allocArray<ByteVar>(response.size)
    response.forEachIndexed { index, value -> result[index] = value }
    return result
}
