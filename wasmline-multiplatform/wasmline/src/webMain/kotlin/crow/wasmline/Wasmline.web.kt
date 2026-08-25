@file:Suppress("unused")

package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineHostDispatcher
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineFailure
import crow.wasmline.web.WebCoreWasmModule
import crow.wasmline.web.WebWasmArtifacts
import crow.wasmline.web.WebWasmPlugin
import crow.wasmline.web.WebWasmRuntime

/**
 * Web-facing Wasmline facade shared by the js and wasmJs targets.
 *
 * This file contains no platform interop: all JS access goes through the
 * expect/actual toolkit in `crow.wasmline.web` (value codec, WebAssembly
 * runtime wrappers, import builder, and Fetch-based artifact cache).
 *
 * Per-module handle used by the platform `Wasmline` actual classes.
 *
 * Date: 2026-07-29
 * Author: crowforkotlin
 */
internal class BrowserWasmline(private val moduleKey: String) {
    fun setOutbound(dispatcher: WasmlineHostDispatcher) {
        WasmlineWebModuleRegistry.require(moduleKey).setDispatcher(dispatcher::dispatch)
    }

    fun call(action: String, inputBytes: ByteArray): ByteArray = WasmlineWebModuleRegistry.require(moduleKey).call(action, inputBytes)

    fun createCoreWasmBackend(): WasmlineCallResult<CoreWasmBackendModule> = WasmlineWebModuleRegistry.coreModule(moduleKey)

    fun invokeRawCarrier(exportName: String, arguments: ByteArray): WasmlineCallResult<ByteArray> = unsupportedTypedInvocation(exportName)

    fun invokeComponentCarrier(exportName: String, arguments: ByteArray): WasmlineCallResult<ByteArray> =
        unsupportedTypedInvocation(exportName)

    fun close() {
        WasmlineWebModuleRegistry.remove(moduleKey)
    }
}

private fun unsupportedTypedInvocation(exportName: String): WasmlineCallResult<ByteArray> = WasmlineCallResult.Failure(
    WasmlineFailure(
        code = WasmlineErrorCode.TRANSPORT_FAILURE,
        message = "Browser host does not support typed export invocation: $exportName.",
    ),
)

/** Lifecycle entry points shared by both web targets.
 *
 * Date: 2026-07-29
 * Author: crowforkotlin
 */
internal object BrowserWasmlineRuntime {
    fun load(
        descriptor: WasmlineArtifactDescriptor,
        config: WasmlineConfig,
        createWasmline: (String, WasmlineConfig, WasmlineArtifactDescriptor) -> Wasmline,
    ): WasmlineLoadState {
        if (config.supportConcurrent) {
            return loadFailure(
                stage = WasmlineLoadStage.ARTIFACT_VALIDATION,
                code = WasmlineErrorCode.CONCURRENT_ACCESS,
                message = "[Wasmline] Browser web host does not support concurrent loading yet.",
            ).toLoadState()
        }

        return WasmlineLocalArtifactBridge.load(
            descriptor = descriptor,
            config = config,
            platform = object : WasmlinePlatformArtifactBridge {
                override fun createWasmline(moduleKey: String, config: WasmlineConfig, descriptor: WasmlineArtifactDescriptor): Wasmline =
                    createWasmline(moduleKey, config, descriptor)

                override fun resolveArtifact(path: String): ResolvedPrecompiledArtifact? = ResolvedPrecompiledArtifact(
                    artifactPath = path,
                    moduleKey = path,
                )

                override fun validationError(descriptor: WasmlineArtifactDescriptor): String? =
                    if (descriptor.executionModel != WasmlineExecutionModel.CORE_WASM ||
                        descriptor.invocationProtocol !in setOf(
                            WasmlineInvocationProtocol.WASMLINE_SERVICE,
                            WasmlineInvocationProtocol.RAW_EXPORT,
                        )
                    ) {
                        "Browser host supports CORE_WASM with WASMLINE_SERVICE or RAW_EXPORT."
                    } else {
                        null
                    }

                override fun backendCodeOrNull(path: String, descriptor: WasmlineArtifactDescriptor): Byte? =
                    if (descriptor.artifactFormat == WasmlineArtifactFormat.RAW_WASM ||
                        path.substringAfterLast('.', "").lowercase() == "wasm"
                    ) {
                        if (descriptor.invocationProtocol == WasmlineInvocationProtocol.RAW_EXPORT) {
                            WasmlineLoadState.CODE_SUCCESS_RAW_EXPORT
                        } else {
                            WasmlineLoadState.CODE_SUCCESS_WASM
                        }
                    } else {
                        null
                    }

                override fun unsupportedArtifactMessage(descriptor: WasmlineArtifactDescriptor): String =
                    "[Wasmline] Browser web host only supports raw .wasm artifacts: ${descriptor.path}"

                override fun loadPrecompiled(moduleKey: String, path: String, descriptor: WasmlineArtifactDescriptor): Boolean =
                    WasmlineWebModuleRegistry.load(moduleKey, path, descriptor)

                override fun loadFailureMessage(descriptor: WasmlineArtifactDescriptor): String =
                    WasmlineWebModuleRegistry.failureMessage(descriptor.path)
            },
        )
    }

    fun preload() = Unit

    fun shutdown() {
        WasmlineWebModuleRegistry.clear()
        WebWasmArtifacts.clear()
    }
}

internal fun browserWasmlinePreload() = BrowserWasmlineRuntime.preload()
internal fun browserWasmlineShutdown() = BrowserWasmlineRuntime.shutdown()
internal fun browserWasmlineWarmUp(engine: WasmlineEngineKind): Nothing =
    throw UnsupportedOperationException("Browser runtimes do not provide the $engine native engine.")
internal fun browserWasmlineLoadArtifact(filepath: String, config: WasmlineConfig): WasmlineLoadState =
    browserWasmlineLoadArtifact(WasmlineArtifactDescriptor(path = filepath), config)

internal fun browserWasmlineLoadArtifact(descriptor: WasmlineArtifactDescriptor, config: WasmlineConfig): WasmlineLoadState =
    BrowserWasmlineRuntime.load(descriptor, config, ::Wasmline)

/**
 * Registry of live plugin modules keyed by module key.
 *
 * Instantiation is synchronous and consumes bytes previously cached by
 * [WasmlineWeb.prefetch]; loading an artifact that was never prefetched
 * fails with an explicit hint instead of blocking the main thread.
 */
private object WasmlineWebModuleRegistry {
    private val serviceModules = linkedMapOf<String, WebWasmPlugin>()
    private val coreModules = linkedMapOf<String, WebCoreWasmModule>()
    private val failures = linkedMapOf<String, String>()

    fun load(moduleKey: String, path: String, descriptor: WasmlineArtifactDescriptor): Boolean {
        if (moduleKey in serviceModules || moduleKey in coreModules) return true

        val bytes = WebWasmArtifacts.bytesOrNull(path)
        if (bytes == null) {
            failures[path] = "Artifact is not prefetched. Call WasmlineWeb.prefetch(\"$path\") and wait for " +
                "completion before WasmlineLoader.load()."
            return false
        }

        return runCatching {
            when (descriptor.invocationProtocol) {
                WasmlineInvocationProtocol.WASMLINE_SERVICE -> WebWasmPlugin(bytes)
                WasmlineInvocationProtocol.RAW_EXPORT -> WebCoreWasmModule(WebWasmRuntime.compile(bytes), descriptor)
                WasmlineInvocationProtocol.COMPONENT_EXPORT -> error("Component exports are not supported by the Web host.")
            }
        }.fold(
            onSuccess = { module ->
                failures.remove(path)
                when (module) {
                    is WebWasmPlugin -> serviceModules[moduleKey] = module
                    is WebCoreWasmModule -> coreModules[moduleKey] = module
                }
                true
            },
            onFailure = { throwable ->
                failures[path] = throwable.message ?: throwable.toString()
                false
            },
        )
    }

    fun require(moduleKey: String): WebWasmPlugin = checkNotNull(serviceModules[moduleKey]) {
        "Wasmline Service web module '$moduleKey' is not loaded."
    }

    fun coreModule(moduleKey: String): WasmlineCallResult<CoreWasmBackendModule> = coreModules[moduleKey]?.let {
        WasmlineCallResult.Success(it)
    } ?: coreFailure(WasmlineErrorCode.INVOCATION_PROTOCOL_MISMATCH, "Raw Core Wasm module '$moduleKey' is not loaded.")

    fun remove(moduleKey: String) {
        serviceModules.remove(moduleKey)?.close()
        coreModules.remove(moduleKey)?.close()
    }

    fun clear() {
        serviceModules.values.forEach { it.close() }
        coreModules.values.forEach { it.close() }
        serviceModules.clear()
        coreModules.clear()
        failures.clear()
    }

    fun failureMessage(path: String): String {
        val detail = failures[path]
        return if (detail == null) {
            "[Wasmline] Web host failed to load artifact: $path"
        } else {
            "[Wasmline] Web host failed to load artifact: $path. $detail"
        }
    }
}
