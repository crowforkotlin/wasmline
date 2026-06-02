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

    actual companion object {
        actual fun bootstrap() {
            loadNativeLibrary()
        }

        actual fun warmup(mode: WasmlineWarmupMode) {
            bootstrap()
            wasmline_warmup_engine(mode == WasmlineWarmupMode.PULLEY)
        }

        actual fun shutdown() {
            bootstrap()
            wasmline_release_engine()
        }

        /**
         * Loads a local precompiled module artifact on iOS.
         */
        actual fun load(
            filepath: String,
            config: WasmlineConfig,
        ): WasmlineLoadState {
            bootstrap()
            val fileManager = NSFileManager.defaultManager
            val isUnsafe = !config.threadSafe
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
    }

    /**
     * Registers the outbound callback bridge for the current module.
     */

    actual internal fun setOutbound(dispatcher: WasmlineHostDispatcher) {
        // Retain the dispatcher so the static C callback can resolve it.
        WasmlineCallbackRegistry.register(moduleKey, dispatcher)

        // iOS requires a top-level static C function pointer.
        wasmline_set_outbound_handler(moduleKey, staticCFunction(::iosStaticOutboundCallback))
    }

    /**
     * Invokes the module inbound entrypoint.
     */
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

// Helpers for C-to-Kotlin outbound callbacks.

/**
 * Registry used to resolve Kotlin dispatchers from static C callbacks.
 */
private object WasmlineCallbackRegistry {
    private val dispatchers = mutableMapOf<String, WasmlineHostDispatcher>()

    fun register(key: String, dispatcher: WasmlineHostDispatcher) {
        // This registry is currently unsynchronized.
        dispatchers[key] = dispatcher
    }

    fun unregister(key: String) {
        dispatchers.remove(key)
    }

    fun get(key: String): WasmlineHostDispatcher? = dispatchers[key]

    // The current C callback contract does not include the module key.
    fun findAny(): WasmlineHostDispatcher? = dispatchers.values.firstOrNull()
}

/**
 * Static callback exported to the native iOS bridge.
 */
fun iosStaticOutboundCallback(
    action: CPointer<ByteVar>?,
    actionLen: ULong,
    payload: CPointer<ByteVar>?,
    payloadLen: ULong
): CPointer<ByteVar>? {
    val actionStr = action?.toKString() ?: ""
    val payloadBytes = payload?.readBytes(payloadLen.toInt()) ?: byteArrayOf()

    // The current native callback does not expose the module key.
    val dispatcher = WasmlineCallbackRegistry.findAny()

    if (dispatcher != null) {
        // C callbacks cannot suspend. Add a synchronous dispatcher path before returning data here.
        val unused = actionStr.length + payloadBytes.size + dispatcher.hashCode()
        if (unused < 0) return null
        return null
    }

    return null
}