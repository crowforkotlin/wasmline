@file:Suppress("unused", "OPTIONAL_DECLARATION_USAGE_IN_NON_COMMON_SOURCE")

package crow.wasmline

import crow.wasmline.extensions.loadNativeLibrary
import crow.wasmline.internal.bridge.WasmlineHostDispatcher
import java.io.File

actual class Wasmline internal actual constructor(
    private val moduleKey: String,
    actual val config: WasmlineConfig,
) {

    actual companion object {

        init { loadNativeLibrary() }

        /**
         * load module
         * @param filepath Local module artifact path, supports `.cwasm` or `.pwasm`
         */
        actual fun load(
            filepath: String,
            threadSafe: Boolean,
            config: WasmlineConfig,
        ): WasmlineLoadState {
            return WasmlineLocalArtifactBridge.load(
                artifactPath = filepath,
                config = config,
                platform = object : WasmlinePlatformArtifactBridge {
                    override fun createWasmline(moduleKey: String, config: WasmlineConfig): Wasmline {
                        return Wasmline(moduleKey, config)
                    }

                    override fun resolveArtifact(path: String): ResolvedPrecompiledArtifact? {
                        val artifactFile = File(path).absoluteFile
                        if (!artifactFile.exists()) return null
                        return ResolvedPrecompiledArtifact(
                            artifactPath = artifactFile.path,
                            moduleKey = artifactFile.path,
                        )
                    }

                    override fun loadPrecompiled(moduleKey: String, path: String): Boolean {
                        return if (threadSafe) nativeLoadAot(key = moduleKey, path = path)
                        else nativeLoadAotUnsafe(key = moduleKey, path = path)
                    }
                },
            )
        }

        actual fun init() { nativeInit() }

        actual fun shutdown() { nativeReleaseEngine() }

        // JNI Methods
        @JvmStatic private external fun nativeLoadAot(key: String, path: String): Boolean
        @JvmStatic private external fun nativeLoadAotUnsafe(key: String, path: String): Boolean
        @JvmStatic private external fun nativeReleaseModule(key: String)
        @JvmStatic private external fun nativeSetOutboundHandler(key: String, dispatcher: WasmlineHostDispatcher)
        @JvmStatic private external fun nativeInvokeInbound(key: String, action: String, protobufBytes: ByteArray): ByteArray
        @JvmStatic private external fun nativeInit()
        @JvmStatic private external fun nativeReleaseEngine()
    }

    actual internal fun setOutbound(dispatcher: WasmlineHostDispatcher) {
        nativeSetOutboundHandler(moduleKey, dispatcher)
    }

    /**
     * Execute Wasm function
     * Supports concurrent calls, the bottom layer will automatically create an independent Session
     */
    actual internal fun call(action: String, inputBytes: ByteArray) : ByteArray = nativeInvokeInbound(moduleKey, action, inputBytes)
    // suspend fun call(action: String, json: String): String = withContext(Dispatchers.Default) { nativeCall(moduleKey, action, json) }

    /**
     * Release the current module
     * Will not affect the Engine, but will free the memory occupied by this module
     */
    actual fun close() { nativeReleaseModule(moduleKey) }
}
