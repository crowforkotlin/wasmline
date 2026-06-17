@file:OptIn(ExperimentalForeignApi::class)

package crow.wasmline

import crow.wasmline.extensions.loadNativeLibrary
import crow.wasmline.native.c.*
import crow.wasmline.internal.bridge.WasmlineHostDispatcher
import kotlinx.cinterop.*
import platform.Foundation.NSFileManager

actual class Wasmline actual internal constructor(
    private val moduleKey: String,
    actual val config: WasmlineConfig,
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

    actual fun close() {
        WasmlineCallbackRegistry.unregister(moduleKey)
        wasmline_release_module(moduleKey)
    }
}

// ========== Runtime bridge functions for WasmlineLoader ==========

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
    if (mode == WasmlineWarmupMode.AOT) {
        WasmlineLog.logger?.warn("[Wasmline] iOS does not support AOT mode (JIT restricted). Forcing PULLEY.")
    }
    wasmline_warmup_engine(true)
}

actual fun wasmlineLoadArtifact(filepath: String, config: WasmlineConfig): WasmlineLoadState {
    iosBootstrap()
    val fileManager = NSFileManager.defaultManager
    val isUnsafe = !config.supportConcurrent
    return WasmlineLocalArtifactBridge.load(
        artifactPath = filepath,
        config = config,
        platform = object : WasmlinePlatformArtifactBridge {
            override fun createWasmline(moduleKey: String, config: WasmlineConfig): Wasmline {
                return Wasmline(moduleKey, config)
            }

            override fun resolveArtifact(path: String): ResolvedPrecompiledArtifact? {
                if (!fileManager.fileExistsAtPath(path)) return null
                return ResolvedPrecompiledArtifact(
                    artifactPath = path,
                    moduleKey = path,
                )
            }

            override fun loadPrecompiled(moduleKey: String, path: String): Boolean {
                return wasmline_load_module(moduleKey, path, isUnsafe)
            }

            override fun loadFailureMessage(path: String): String {
                return "[Wasmline] Native artifact load failed for: $path"
            }
        },
    )
}

// Helpers for C-to-Kotlin outbound callbacks.

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
    payloadLen: ULong
): CPointer<ByteVar>? {
    val actionStr = action?.toKString() ?: ""
    val payloadBytes = payload?.readBytes(payloadLen.toInt()) ?: byteArrayOf()

    val dispatcher = WasmlineCallbackRegistry.findAny()

    if (dispatcher != null) {
        val unused = actionStr.length + payloadBytes.size + dispatcher.hashCode()
        if (unused < 0) return null
        return null
    }

    return null
}
