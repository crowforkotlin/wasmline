@file:Suppress("unused", "OPTIONAL_DECLARATION_USAGE_IN_NON_COMMON_SOURCE")

package crow.wasmline

import crow.wasmline.extensions.loadNativeLibrary
import crow.wasmline.internal.bridge.WasmlineHostDispatcher
import java.io.File

actual class Wasmline internal actual constructor(
    private val moduleKey: String,
    actual val config: WasmlineConfig,
) {

    internal companion object {
        // JNI native methods — must stay private for correct JNI name mangling.
        @JvmStatic private external fun nativeLoadAot(key: String, path: String): Boolean
        @JvmStatic private external fun nativeLoadAotUnsafe(key: String, path: String): Boolean
        @JvmStatic private external fun nativeReleaseModule(key: String)
        @JvmStatic private external fun nativeSetOutboundHandler(key: String, dispatcher: WasmlineHostDispatcher)
        @JvmStatic private external fun nativeInvokeInbound(key: String, action: String, protobufBytes: ByteArray): ByteArray
        @JvmStatic private external fun nativeWarmup(usePulley: Boolean)
        @JvmStatic private external fun nativeReleaseEngine()

        // Internal wrappers for use by standalone bridge functions.
        fun loadAot(key: String, path: String): Boolean = nativeLoadAot(key, path)
        fun loadAotUnsafe(key: String, path: String): Boolean = nativeLoadAotUnsafe(key, path)
        fun warmupEngine(usePulley: Boolean) = nativeWarmup(usePulley)
        fun releaseEngine() = nativeReleaseEngine()
    }

    actual internal fun setOutbound(dispatcher: WasmlineHostDispatcher) {
        nativeSetOutboundHandler(moduleKey, dispatcher)
    }

    actual internal fun call(action: String, inputBytes: ByteArray): ByteArray =
        nativeInvokeInbound(moduleKey, action, inputBytes)

    actual fun close() { nativeReleaseModule(moduleKey) }
}

// ========== Runtime bridge functions for WasmlineLoader ==========

@Volatile
private var jniBootstrapped = false

private fun ensureBootstrapped() {
    if (jniBootstrapped) return
    synchronized(Wasmline) {
        if (jniBootstrapped) return
        loadNativeLibrary()
        jniBootstrapped = true
    }
}

internal actual fun wasmlineBootstrap() {
    ensureBootstrapped()
}

internal actual fun wasmlineShutdown() {
    ensureBootstrapped()
    Wasmline.releaseEngine()
}

internal actual fun wasmlineWarmup(mode: WasmlineWarmupMode) {
    ensureBootstrapped()
    Wasmline.warmupEngine(mode == WasmlineWarmupMode.PULLEY)
}

internal actual fun wasmlineLoadArtifact(filepath: String, config: WasmlineConfig): WasmlineLoadState {
    ensureBootstrapped()
    val supportConcurrent = config.supportConcurrent
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
                return if (supportConcurrent) Wasmline.loadAot(key = moduleKey, path = path)
                else Wasmline.loadAotUnsafe(key = moduleKey, path = path)
            }
        },
    )
}
