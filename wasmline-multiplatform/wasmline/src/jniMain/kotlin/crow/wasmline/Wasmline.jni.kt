@file:Suppress("unused")

package crow.wasmline

import crow.wasmline.extensions.loadNativeLibrary
import crow.wasmline.internal.bridge.WasmlineHostDispatcher
import crow.wasmline.invocation.WasmlineCallError
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import java.io.File

actual class Wasmline internal actual constructor(
    private val moduleKey: String,
    actual val config: WasmlineConfig,
    actual val descriptor: WasmlineArtifactDescriptor,
) {

    internal companion object {
        @JvmStatic private external fun nativeLoadAot(key: String, path: String): Boolean

        @JvmStatic private external fun nativeLoadAotUnsafe(key: String, path: String): Boolean

        @JvmStatic private external fun nativeLoadAotWithFormat(key: String, path: String, formatCode: Int): Boolean

        @JvmStatic private external fun nativeLoadAotUnsafeWithFormat(key: String, path: String, formatCode: Int): Boolean

        @JvmStatic private external fun nativeLoadComponent(key: String, path: String): Boolean

        @JvmStatic private external fun nativeLoadComponentUnsafe(key: String, path: String): Boolean

        @JvmStatic private external fun nativeLoadComponentWithFormat(key: String, path: String, formatCode: Int): Boolean

        @JvmStatic private external fun nativeLoadComponentUnsafeWithFormat(key: String, path: String, formatCode: Int): Boolean

        @JvmStatic private external fun nativeReleaseModule(key: String)

        @JvmStatic private external fun nativeSetOutboundHandler(key: String, codec: String, dispatcher: WasmlineHostDispatcher)

        @JvmStatic private external fun nativeSetComponentHostHandler(key: String, dispatcher: WasmlineComponentHostDispatcher): Boolean

        @JvmStatic private external fun nativeInvokeInbound(key: String, action: String, protobufBytes: ByteArray): ByteArray

        @JvmStatic private external fun nativeInvokeRaw(key: String, exportName: String, arguments: ByteArray): ByteArray?

        @JvmStatic private external fun nativeInvokeComponent(key: String, exportName: String, arguments: ByteArray): ByteArray?

        @JvmStatic private external fun nativeWarmup(usePulley: Boolean)

        @JvmStatic private external fun nativeSupportsAot(): Boolean

        @JvmStatic private external fun nativeWasmtimeVersion(): String

        @JvmStatic private external fun nativeSupportsCranelift(): Boolean

        @JvmStatic private external fun nativeSupportsPulley(): Boolean

        @JvmStatic private external fun nativeReleaseEngine()

        @JvmStatic private external fun nativeResetAotLoadPathDiagnostics()

        @JvmStatic private external fun nativeAotLoadPathDiagnostics(): Long

        fun loadAot(key: String, path: String): Boolean = nativeLoadAot(key, path)
        fun loadAotUnsafe(key: String, path: String): Boolean = nativeLoadAotUnsafe(key, path)
        fun loadAot(key: String, path: String, artifactFormat: WasmlineArtifactFormat): Boolean =
            nativeLoadAotWithFormat(key, path, artifactFormat.nativeBridgeCode())

        fun loadAotUnsafe(key: String, path: String, artifactFormat: WasmlineArtifactFormat): Boolean =
            nativeLoadAotUnsafeWithFormat(key, path, artifactFormat.nativeBridgeCode())

        fun loadComponent(key: String, path: String): Boolean = nativeLoadComponent(key, path)
        fun loadComponentUnsafe(key: String, path: String): Boolean = nativeLoadComponentUnsafe(key, path)
        fun loadComponent(key: String, path: String, artifactFormat: WasmlineArtifactFormat): Boolean =
            nativeLoadComponentWithFormat(key, path, artifactFormat.nativeBridgeCode())

        fun loadComponentUnsafe(key: String, path: String, artifactFormat: WasmlineArtifactFormat): Boolean =
            nativeLoadComponentUnsafeWithFormat(key, path, artifactFormat.nativeBridgeCode())

        fun warmupEngine(usePulley: Boolean) = nativeWarmup(usePulley)
        fun supportsAot(): Boolean = nativeSupportsAot()
        fun runtimeCapabilities(): WasmlineRuntimeCapabilities = WasmlineRuntimeCapabilities(
            wasmtimeVersion = nativeWasmtimeVersion(),
            supportsCranelift = nativeSupportsCranelift(),
            supportsPulley = nativeSupportsPulley(),
            targetOs = jniTargetOs(),
            targetCpu = jniTargetCpu(),
            is64Bit = jniIs64Bit(),
        )
        fun releaseEngine() = nativeReleaseEngine()
        fun resetAotLoadPathDiagnostics() = nativeResetAotLoadPathDiagnostics()
        fun aotLoadPathDiagnostics(): Long = nativeAotLoadPathDiagnostics()
    }

    internal actual fun setOutbound(dispatcher: WasmlineHostDispatcher) {
        nativeSetOutboundHandler(moduleKey, config.serialization.factoryId, dispatcher)
    }

    internal actual fun setComponentHostDispatcher(dispatcher: WasmlineComponentHostDispatcher) {
        check(nativeSetComponentHostHandler(moduleKey, dispatcher)) {
            "Failed to install the typed Component host dispatcher for '$moduleKey'."
        }
    }

    internal actual fun call(action: String, inputBytes: ByteArray): ByteArray = nativeInvokeInbound(moduleKey, action, inputBytes)

    internal actual fun invokeRawCarrier(exportName: String, arguments: ByteArray): WasmlineCallResult<ByteArray> =
        decodeNativeCarrier(nativeInvokeRaw(moduleKey, exportName, arguments))

    internal actual fun invokeComponentCarrier(exportName: String, arguments: ByteArray): WasmlineCallResult<ByteArray> =
        decodeNativeCarrier(nativeInvokeComponent(moduleKey, exportName, arguments))

    actual fun close() {
        nativeReleaseModule(moduleKey)
    }
}

private fun decodeNativeCarrier(bytes: ByteArray?): WasmlineCallResult<ByteArray> = if (bytes == null) {
    WasmlineCallResult.Failure(
        WasmlineCallError(
            code = WasmlineErrorCode.TRANSPORT_FAILURE,
            message = "JNI typed invocation returned no response.",
        ),
    )
} else {
    WasmlineCallResult.Success(bytes)
}

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

actual fun wasmlineBootstrap() {
    ensureBootstrapped()
}

actual fun wasmlineShutdown() {
    ensureBootstrapped()
    Wasmline.releaseEngine()
}

internal data class WasmlineAotLoadPathDiagnostics(
    val coreDeserializeSuccesses: Int,
    val componentDeserializeSuccesses: Int,
    val moduleNewCalls: Int,
    val componentNewCalls: Int,
)

internal fun wasmlineResetAotLoadPathDiagnostics() {
    ensureBootstrapped()
    Wasmline.resetAotLoadPathDiagnostics()
}

internal fun wasmlineAotLoadPathDiagnostics(): WasmlineAotLoadPathDiagnostics {
    ensureBootstrapped()
    val snapshot = Wasmline.aotLoadPathDiagnostics()
    return WasmlineAotLoadPathDiagnostics(
        coreDeserializeSuccesses = snapshot.aotLoadPathField(0),
        componentDeserializeSuccesses = snapshot.aotLoadPathField(16),
        moduleNewCalls = snapshot.aotLoadPathField(32),
        componentNewCalls = snapshot.aotLoadPathField(48),
    )
}

private fun Long.aotLoadPathField(shift: Int): Int = ((this ushr shift) and 0xffffL).toInt()

actual fun wasmlineWarmup(mode: WasmlineWarmupMode) {
    ensureBootstrapped()
    val effectiveMode = when {
        mode == WasmlineWarmupMode.CRANELIFT && !Wasmline.supportsAot() -> {
            WasmlineLog.logger?.warn(
                "[Wasmline] CRANELIFT warmup requested but the current engine does not include the Cranelift compiler. " +
                    "Falling back to PULLEY. To use CRANELIFT warmup, switch to wasmline-engine-cranelift.",
            )
            WasmlineWarmupMode.PULLEY
        }

        else -> mode
    }
    Wasmline.warmupEngine(effectiveMode == WasmlineWarmupMode.PULLEY)
}

internal actual fun wasmlineRuntimeCapabilities(): WasmlineRuntimeCapabilities {
    ensureBootstrapped()
    return Wasmline.runtimeCapabilities()
}

actual fun wasmlineNativeRuntimeInfo(): WasmlineNativeRuntimeInfo? = wasmlineRuntimeCapabilities().nativeRuntimeInfo

actual fun wasmlineLoadArtifact(filepath: String, config: WasmlineConfig): WasmlineLoadState =
    wasmlineLoadArtifact(WasmlineArtifactDescriptor(path = filepath), config)

actual fun wasmlineLoadArtifact(descriptor: WasmlineArtifactDescriptor, config: WasmlineConfig): WasmlineLoadState {
    val supportConcurrent = config.supportConcurrent
    return WasmlineLocalArtifactBridge.load(
        descriptor = descriptor,
        config = config,
        platform = object : WasmlinePlatformArtifactBridge {
            override fun createWasmline(moduleKey: String, config: WasmlineConfig, descriptor: WasmlineArtifactDescriptor): Wasmline =
                Wasmline(moduleKey, config, descriptor)

            override fun resolveArtifact(path: String): ResolvedPrecompiledArtifact? {
                val artifactFile = File(path).absoluteFile
                if (!artifactFile.exists()) return null
                return ResolvedPrecompiledArtifact(
                    artifactPath = artifactFile.path,
                    moduleKey = artifactFile.path,
                )
            }

            override fun validationError(descriptor: WasmlineArtifactDescriptor): String? =
                descriptor.runtimeCompatibilityError(wasmlineRuntimeCapabilities())

            override fun requiresExplicitArtifactFormat(): Boolean = true

            override fun loadPrecompiled(moduleKey: String, path: String, descriptor: WasmlineArtifactDescriptor): Boolean {
                ensureBootstrapped()
                val artifactFormat = descriptor.artifactFormat ?: return false
                return when (descriptor.executionModel) {
                    WasmlineExecutionModel.CORE_WASM ->
                        if (supportConcurrent) {
                            Wasmline.loadAot(moduleKey, path, artifactFormat)
                        } else {
                            Wasmline.loadAotUnsafe(moduleKey, path, artifactFormat)
                        }

                    WasmlineExecutionModel.COMPONENT_MODEL ->
                        if (supportConcurrent) {
                            Wasmline.loadComponent(moduleKey, path, artifactFormat)
                        } else {
                            Wasmline.loadComponentUnsafe(moduleKey, path, artifactFormat)
                        }
                }
            }
        },
    )
}

private fun jniTargetOs(): String {
    val runtimeName = System.getProperty("java.runtime.name").orEmpty()
    val vmName = System.getProperty("java.vm.name").orEmpty()
    if (runtimeName.contains("android", ignoreCase = true) || vmName.contains("dalvik", ignoreCase = true)) {
        return "android"
    }
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        "mac" in osName -> "macos"
        "win" in osName -> "windows"
        "linux" in osName -> "linux"
        else -> osName.ifBlank { "unknown" }
    }
}

private fun jniTargetCpu(): String = when (val arch = System.getProperty("os.arch").orEmpty().lowercase()) {
    "amd64", "x86_64" -> "x86_64"
    "arm64", "aarch64" -> "aarch64"
    else -> arch.ifBlank { "unknown" }
}

private fun jniIs64Bit(): Boolean {
    val arch = System.getProperty("os.arch").orEmpty().lowercase()
    return "64" in arch || arch == "aarch64" || arch == "arm64"
}
