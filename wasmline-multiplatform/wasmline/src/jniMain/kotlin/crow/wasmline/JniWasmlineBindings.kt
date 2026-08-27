package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineHostDispatcher

/**
 * Exposes JNI symbols through narrow Kotlin adapters for the native Wasmline runtime.
 *
 * Date: 2026-08-26
 * Author: crowforkotlin
 */
internal object JniWasmlineBindings {
    @JvmStatic
    private external fun nativeLoadAotWithFormat(key: String, path: String, formatCode: Int): Boolean

    @JvmStatic
    private external fun nativeLoadAotUnsafeWithFormat(key: String, path: String, formatCode: Int): Boolean

    @JvmStatic
    private external fun nativeLoadComponentWithFormat(key: String, path: String, formatCode: Int): Boolean

    @JvmStatic
    private external fun nativeLoadComponentUnsafeWithFormat(key: String, path: String, formatCode: Int): Boolean

    @JvmStatic
    private external fun nativeReleaseModule(key: String)

    @JvmStatic
    private external fun nativeSetOutboundHandler(key: String, codec: String, dispatcher: WasmlineHostDispatcher)

    @JvmStatic
    private external fun nativeSetComponentHostHandler(key: String, dispatcher: WasmlineComponentHostDispatcher): Boolean

    @JvmStatic
    private external fun nativeInvokeInbound(key: String, action: String, protobufBytes: ByteArray): ByteArray

    @JvmStatic
    private external fun nativeInvokeRaw(key: String, exportName: String, arguments: ByteArray): ByteArray?

    @JvmStatic
    private external fun nativeCoreModuleExports(key: String): ByteArray?

    @JvmStatic
    private external fun nativeCoreCreateSession(
        artifactKey: String,
        sessionKey: String,
        imports: ByteArray,
        dispatcher: Any,
        memoryExportName: String?,
    ): ByteArray?

    @JvmStatic
    private external fun nativeCoreInvoke(sessionKey: String, exportName: String, arguments: ByteArray): ByteArray?

    @JvmStatic
    private external fun nativeCoreReleaseSession(sessionKey: String)

    @JvmStatic
    private external fun nativeCoreMemorySize(sessionKey: String, pages: Boolean): ByteArray?

    @JvmStatic
    private external fun nativeCoreMemoryReadInto(
        sessionKey: String,
        sourceOffset: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): ByteArray?

    @JvmStatic
    private external fun nativeCoreMemoryWriteFrom(
        sessionKey: String,
        source: ByteArray,
        sourceOffset: Int,
        destinationOffset: Long,
        length: Int,
    ): ByteArray?

    @JvmStatic
    private external fun nativeCoreMemoryGrow(sessionKey: String, deltaPages: Long): ByteArray?

    @JvmStatic
    private external fun nativeInvokeComponent(key: String, exportName: String, arguments: ByteArray): ByteArray?

    @JvmStatic
    private external fun nativeInstantiateComponent(
        artifactKey: String,
        instanceKey: String,
        dispatcher: WasmlineComponentHostDispatcher,
    ): Boolean

    @JvmStatic
    private external fun nativeInvokeComponentInstance(instanceKey: String, exportName: String, arguments: ByteArray): ByteArray?

    @JvmStatic
    private external fun nativeReleaseComponentInstance(instanceKey: String)

    @JvmStatic
    private external fun nativeDropComponentResource(instanceKey: String, resource: ByteArray): Boolean

    @JvmStatic
    private external fun nativeCreateComponentHostResource(
        instanceKey: String,
        interfaceId: String,
        resourceName: String,
        representation: Int,
    ): ByteArray?

    @JvmStatic
    private external fun nativeWarmUp(usePulley: Boolean): Boolean

    @JvmStatic
    private external fun nativeWasmtimeVersion(): String

    @JvmStatic
    private external fun nativeSupportsCranelift(): Boolean

    @JvmStatic
    private external fun nativeSupportsPulley(): Boolean

    @JvmStatic
    private external fun nativeReleaseEngine()

    @JvmStatic
    private external fun nativeResetAotLoadPathDiagnostics()

    @JvmStatic
    private external fun nativeAotLoadPathDiagnostics(): Long

    fun loadModule(key: String, path: String, artifactFormat: WasmlineArtifactFormat): Boolean =
        nativeLoadAotWithFormat(key, path, artifactFormat.nativeBridgeCode())

    fun loadModuleUnsafe(key: String, path: String, artifactFormat: WasmlineArtifactFormat): Boolean =
        nativeLoadAotUnsafeWithFormat(key, path, artifactFormat.nativeBridgeCode())

    fun loadComponent(key: String, path: String, artifactFormat: WasmlineArtifactFormat): Boolean =
        nativeLoadComponentWithFormat(key, path, artifactFormat.nativeBridgeCode())

    fun loadComponentUnsafe(key: String, path: String, artifactFormat: WasmlineArtifactFormat): Boolean =
        nativeLoadComponentUnsafeWithFormat(key, path, artifactFormat.nativeBridgeCode())

    fun releaseModule(key: String) = nativeReleaseModule(key)

    fun setOutboundHandler(key: String, codec: String, dispatcher: WasmlineHostDispatcher) =
        nativeSetOutboundHandler(key, codec, dispatcher)

    fun setComponentHostHandler(key: String, dispatcher: WasmlineComponentHostDispatcher): Boolean =
        nativeSetComponentHostHandler(key, dispatcher)

    fun invokeInbound(key: String, action: String, input: ByteArray): ByteArray = nativeInvokeInbound(key, action, input)

    fun invokeRaw(key: String, exportName: String, arguments: ByteArray): ByteArray? = nativeInvokeRaw(key, exportName, arguments)

    fun coreModuleExports(key: String): ByteArray? = nativeCoreModuleExports(key)

    fun coreCreateSession(
        artifactKey: String,
        sessionKey: String,
        imports: ByteArray,
        dispatcher: Any,
        memoryExportName: String?,
    ): ByteArray? = nativeCoreCreateSession(artifactKey, sessionKey, imports, dispatcher, memoryExportName)

    fun coreInvoke(sessionKey: String, exportName: String, arguments: ByteArray): ByteArray? =
        nativeCoreInvoke(sessionKey, exportName, arguments)

    fun coreReleaseSession(sessionKey: String) = nativeCoreReleaseSession(sessionKey)

    fun coreMemorySize(sessionKey: String, pages: Boolean): ByteArray? = nativeCoreMemorySize(sessionKey, pages)

    fun coreMemoryReadInto(
        sessionKey: String,
        sourceOffset: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): ByteArray? = nativeCoreMemoryReadInto(sessionKey, sourceOffset, destination, destinationOffset, length)

    fun coreMemoryWriteFrom(sessionKey: String, source: ByteArray, sourceOffset: Int, destinationOffset: Long, length: Int): ByteArray? =
        nativeCoreMemoryWriteFrom(sessionKey, source, sourceOffset, destinationOffset, length)

    fun coreMemoryGrow(sessionKey: String, deltaPages: Long): ByteArray? = nativeCoreMemoryGrow(sessionKey, deltaPages)

    fun invokeComponent(key: String, exportName: String, arguments: ByteArray): ByteArray? =
        nativeInvokeComponent(key, exportName, arguments)

    fun instantiateComponent(artifactKey: String, instanceKey: String, dispatcher: WasmlineComponentHostDispatcher): Boolean =
        nativeInstantiateComponent(artifactKey, instanceKey, dispatcher)

    fun invokeComponentInstance(instanceKey: String, exportName: String, arguments: ByteArray): ByteArray? =
        nativeInvokeComponentInstance(instanceKey, exportName, arguments)

    fun releaseComponentInstance(instanceKey: String) = nativeReleaseComponentInstance(instanceKey)

    fun dropComponentResource(instanceKey: String, resource: ByteArray): Boolean = nativeDropComponentResource(instanceKey, resource)

    fun createComponentHostResource(instanceKey: String, interfaceId: String, resourceName: String, representation: Int): ByteArray? =
        nativeCreateComponentHostResource(instanceKey, interfaceId, resourceName, representation)

    fun warmUp(engine: WasmlineEngineKind): Boolean = nativeWarmUp(engine == WasmlineEngineKind.PULLEY)

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

    internal fun loadModuleWithFormatCode(key: String, path: String, formatCode: Int): Boolean =
        nativeLoadAotWithFormat(key, path, formatCode)
}
