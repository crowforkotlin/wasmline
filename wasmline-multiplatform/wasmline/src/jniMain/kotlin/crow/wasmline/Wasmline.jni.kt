@file:Suppress("unused")

package crow.wasmline

import crow.wasmline.extensions.ensureNativeRuntimeLoaded
import crow.wasmline.internal.WasmlineComponentBindings
import crow.wasmline.internal.bridge.WasmlineHostDispatcher
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineFailure
import java.io.File

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
        JniWasmlineBindings.setOutboundHandler(moduleKey, config.serialization.factoryId, dispatcher)
    }

    internal actual fun setComponentHostDispatcher(dispatcher: WasmlineComponentHostDispatcher) {
        check(JniWasmlineBindings.setComponentHostHandler(moduleKey, dispatcher)) {
            "Failed to install the typed Component host dispatcher for '$moduleKey'."
        }
    }

    internal actual fun call(action: String, inputBytes: ByteArray): ByteArray =
        JniWasmlineBindings.invokeInbound(moduleKey, action, inputBytes)

    internal actual fun invokeRawCarrier(exportName: String, arguments: ByteArray): WasmlineCallResult<ByteArray> =
        decodeNativeCarrier(JniWasmlineBindings.invokeRaw(moduleKey, exportName, arguments))

    internal actual fun createCoreWasmBackend(): WasmlineCallResult<CoreWasmBackendModule> = createJniCoreWasmBackend(moduleKey, descriptor)

    actual fun asCoreWasmModule(): WasmlineCallResult<CoreWasmModule> = createCoreWasmModule(this)

    internal actual fun invokeComponentCarrier(exportName: String, arguments: ByteArray): WasmlineCallResult<ByteArray> =
        decodeNativeCarrier(JniWasmlineBindings.invokeComponent(moduleKey, exportName, arguments))

    internal actual fun instantiateComponentInstance(instanceKey: String, dispatcher: WasmlineComponentHostDispatcher): Boolean =
        JniWasmlineBindings.instantiateComponent(moduleKey, instanceKey, dispatcher)

    internal actual fun invokeComponentInstanceCarrier(
        instanceKey: String,
        exportName: String,
        arguments: ByteArray,
    ): WasmlineCallResult<ByteArray> = decodeNativeCarrier(JniWasmlineBindings.invokeComponentInstance(instanceKey, exportName, arguments))

    internal actual fun releaseComponentInstance(instanceKey: String) = JniWasmlineBindings.releaseComponentInstance(instanceKey)

    internal actual fun dropComponentResource(instanceKey: String, reference: WasmlineComponentValue.ResourceValue): Boolean {
        val encoded = WasmlineTypedInvocationCodec.encodeComponentArguments(listOf(reference))
        return encoded is WasmlineCallResult.Success && JniWasmlineBindings.dropComponentResource(instanceKey, encoded.value)
    }

    internal actual fun createComponentHostResource(
        instanceKey: String,
        interfaceId: String,
        resourceName: String,
        representation: UInt,
    ): WasmlineCallResult<WasmlineComponentValue.ResourceValue> = decodeResourceCarrier(
        JniWasmlineBindings.createComponentHostResource(instanceKey, interfaceId, resourceName, representation.toInt()),
    )

    actual fun close() {
        componentModuleState.close()
        hostServiceRegistry.clear()
        JniWasmlineBindings.releaseModule(moduleKey)
    }
}

private fun decodeNativeCarrier(bytes: ByteArray?): WasmlineCallResult<ByteArray> = if (bytes == null) {
    WasmlineCallResult.Failure(
        WasmlineFailure(
            code = WasmlineErrorCode.TRANSPORT_FAILURE,
            message = "JNI typed invocation returned no response.",
        ),
    )
} else {
    WasmlineCallResult.Success(bytes)
}

private fun decodeResourceCarrier(bytes: ByteArray?): WasmlineCallResult<WasmlineComponentValue.ResourceValue> =
    when (val carrier = decodeNativeCarrier(bytes)) {
        is WasmlineCallResult.Failure -> carrier

        is WasmlineCallResult.Success -> when (val decoded = WasmlineTypedInvocationCodec.decodeComponentArguments(carrier.value)) {
            is WasmlineCallResult.Failure -> decoded

            is WasmlineCallResult.Success -> {
                val resource = decoded.value.singleOrNull() as? WasmlineComponentValue.ResourceValue
                if (resource != null) {
                    WasmlineCallResult.Success(resource)
                } else {
                    WasmlineCallResult.Failure(
                        WasmlineFailure(WasmlineErrorCode.COMPONENT_RESOURCE_INVALID, "Native Host resource carrier is invalid."),
                    )
                }
            }
        }
    }

internal actual class WasmlineHostServiceLock {
    actual fun <T> withLock(block: () -> T): T = synchronized(this, block)
}

@Volatile
private var jniRuntimeLoaded = false

internal fun ensureJniRuntimeLoaded() {
    if (jniRuntimeLoaded) return
    synchronized(JniWasmlineBindings) {
        if (jniRuntimeLoaded) return
        ensureNativeRuntimeLoaded()
        JniWasmlineBindings.runtimeCapabilities()
        jniRuntimeLoaded = true
    }
}

internal actual fun platformWasmlinePreload() {
    ensureJniRuntimeLoaded()
}

internal actual fun platformWasmlineShutdown() {
    if (!jniRuntimeLoaded) return
    JniWasmlineBindings.releaseEngine()
}

internal data class WasmlineAotLoadPathDiagnostics(
    val coreDeserializeSuccesses: Int,
    val componentDeserializeSuccesses: Int,
    val moduleNewCalls: Int,
    val componentNewCalls: Int,
)

internal fun wasmlineResetAotLoadPathDiagnostics() {
    ensureJniRuntimeLoaded()
    JniWasmlineBindings.resetAotLoadPathDiagnostics()
}

internal fun wasmlineAotLoadPathDiagnostics(): WasmlineAotLoadPathDiagnostics {
    ensureJniRuntimeLoaded()
    val snapshot = JniWasmlineBindings.aotLoadPathDiagnostics()
    return WasmlineAotLoadPathDiagnostics(
        coreDeserializeSuccesses = snapshot.aotLoadPathField(0),
        componentDeserializeSuccesses = snapshot.aotLoadPathField(16),
        moduleNewCalls = snapshot.aotLoadPathField(32),
        componentNewCalls = snapshot.aotLoadPathField(48),
    )
}

private fun Long.aotLoadPathField(shift: Int): Int = ((this ushr shift) and 0xffffL).toInt()

internal actual fun platformWasmlineWarmUp(engine: WasmlineEngineKind) {
    ensureJniRuntimeLoaded()
    val capabilities = JniWasmlineBindings.runtimeCapabilities()
    val supported = engine in requireNotNull(capabilities.nativeRuntimeInfo).supportedEngines
    require(supported) {
        "The linked Wasmline runtime does not support the $engine engine."
    }
    check(JniWasmlineBindings.warmUp(engine)) {
        "Cannot select the $engine engine while artifacts for another engine are still loaded."
    }
}

internal actual fun platformWasmlineRuntimeCapabilities(): WasmlineRuntimeCapabilities {
    ensureJniRuntimeLoaded()
    return JniWasmlineBindings.runtimeCapabilities()
}

internal actual fun platformWasmlineNativeRuntimeInfo(): WasmlineNativeRuntimeInfo? =
    platformWasmlineRuntimeCapabilities().nativeRuntimeInfo

internal actual fun platformWasmlineLoadArtifact(filepath: String, config: WasmlineConfig): WasmlineLoadState =
    platformWasmlineLoadArtifact(WasmlineArtifactDescriptor(path = filepath), config)

internal actual fun platformWasmlineLoadArtifact(descriptor: WasmlineArtifactDescriptor, config: WasmlineConfig): WasmlineLoadState {
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
                descriptor.runtimeCompatibilityError(platformWasmlineRuntimeCapabilities())

            override fun requiresExplicitArtifactFormat(): Boolean = true

            override fun loadPrecompiled(moduleKey: String, path: String, descriptor: WasmlineArtifactDescriptor): Boolean {
                ensureJniRuntimeLoaded()
                val artifactFormat = descriptor.artifactFormat ?: return false
                return when (descriptor.executionModel) {
                    WasmlineExecutionModel.CORE_WASM ->
                        if (supportConcurrent) {
                            JniWasmlineBindings.loadModule(moduleKey, path, artifactFormat)
                        } else {
                            JniWasmlineBindings.loadModuleUnsafe(moduleKey, path, artifactFormat)
                        }

                    WasmlineExecutionModel.COMPONENT_MODEL ->
                        if (supportConcurrent) {
                            JniWasmlineBindings.loadComponent(moduleKey, path, artifactFormat)
                        } else {
                            JniWasmlineBindings.loadComponentUnsafe(moduleKey, path, artifactFormat)
                        }
                }
            }
        },
    )
}

internal fun jniTargetOs(): String {
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

internal fun jniTargetCpu(): String = when (val arch = System.getProperty("os.arch").orEmpty().lowercase()) {
    "amd64", "x86_64" -> "x86_64"
    "arm64", "aarch64" -> "aarch64"
    else -> arch.ifBlank { "unknown" }
}

internal fun jniIs64Bit(): Boolean {
    val arch = System.getProperty("os.arch").orEmpty().lowercase()
    return "64" in arch || arch == "aarch64" || arch == "arm64"
}
