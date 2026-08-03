@file:Suppress("unused")

package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineHostDispatcher
import crow.wasmline.invocation.WasmlineCallError
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.web.WebWasmArtifacts
import crow.wasmline.web.WebWasmPlugin

/**
 * Web-facing Wasmline facade shared by the js and wasmJs targets.
 *
 * This file contains no platform interop: all JS access goes through the
 * expect/actual toolkit in `crow.wasmline.web` (value codec, WebAssembly
 * runtime wrappers, import builder, and Fetch-based artifact cache).
 *
 * 2026-07-29
 * @author crowforkotlin
 */

/** Per-module handle used by the platform `Wasmline` actual classes.
 *
 * 2026-07-29
 * @author crowforkotlin
 */
internal class BrowserWasmline(private val moduleKey: String) {
    fun setOutbound(dispatcher: WasmlineHostDispatcher) {
        WasmlineWebModuleRegistry.require(moduleKey).setDispatcher(dispatcher::dispatch)
    }

    fun call(action: String, inputBytes: ByteArray): ByteArray = WasmlineWebModuleRegistry.require(moduleKey).call(action, inputBytes)

    fun invokeRawCarrier(exportName: String, arguments: ByteArray): WasmlineCallResult<ByteArray> = unsupportedTypedInvocation(exportName)

    fun invokeComponentCarrier(exportName: String, arguments: ByteArray): WasmlineCallResult<ByteArray> =
        unsupportedTypedInvocation(exportName)

    fun close() {
        WasmlineWebModuleRegistry.remove(moduleKey)
    }
}

private fun unsupportedTypedInvocation(exportName: String): WasmlineCallResult<ByteArray> = WasmlineCallResult.Failure(
    WasmlineCallError(
        code = WasmlineErrorCode.TRANSPORT_FAILURE,
        message = "Browser host does not support typed export invocation: $exportName.",
    ),
)

/** Lifecycle entry points shared by both web targets.
 *
 * 2026-07-29
 * @author crowforkotlin
 */
internal object BrowserWasmlineRuntime {
    fun load(
        descriptor: WasmlineArtifactDescriptor,
        config: WasmlineConfig,
        createWasmline: (String, WasmlineConfig, WasmlineArtifactDescriptor) -> Wasmline,
    ): WasmlineLoadState {
        if (config.supportConcurrent) {
            return WasmlineLoadState.Failure(
                code = WasmlineLoadState.CODE_FAILURE,
                cause = "[Wasmline] Browser web host does not support concurrent loading yet.",
            )
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
                        descriptor.invocationProtocol != WasmlineInvocationProtocol.WASMLINE_CORE_V1
                    ) {
                        "Browser host supports only CORE_WASM with WASMLINE_CORE_V1."
                    } else {
                        null
                    }

                override fun backendCodeOrNull(path: String, descriptor: WasmlineArtifactDescriptor): Byte? =
                    if (path.substringAfterLast('.', "").lowercase() ==
                        "wasm"
                    ) {
                        WasmlineLoadState.CODE_SUCCESS_WASM
                    } else {
                        null
                    }

                override fun unsupportedArtifactMessage(descriptor: WasmlineArtifactDescriptor): String =
                    "[Wasmline] Browser web host only supports raw .wasm artifacts: ${descriptor.path}"

                override fun loadPrecompiled(moduleKey: String, path: String, descriptor: WasmlineArtifactDescriptor): Boolean =
                    WasmlineWebModuleRegistry.load(moduleKey, path)

                override fun loadFailureMessage(descriptor: WasmlineArtifactDescriptor): String =
                    WasmlineWebModuleRegistry.failureMessage(descriptor.path)
            },
        )
    }

    fun bootstrap() = Unit

    fun shutdown() {
        WasmlineWebModuleRegistry.clear()
        WebWasmArtifacts.clear()
    }
}

internal fun browserWasmlineBootstrap() = BrowserWasmlineRuntime.bootstrap()
internal fun browserWasmlineShutdown() = BrowserWasmlineRuntime.shutdown()
internal fun browserWasmlineWarmup(@Suppress("UNUSED_PARAMETER") mode: WasmlineWarmupMode) = Unit
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
    private val modules = linkedMapOf<String, WebWasmPlugin>()
    private val failures = linkedMapOf<String, String>()

    fun load(moduleKey: String, path: String): Boolean {
        if (modules.containsKey(moduleKey)) return true

        val bytes = WebWasmArtifacts.bytesOrNull(path)
        if (bytes == null) {
            failures[path] = "Artifact is not prefetched. Call WasmlineWeb.prefetch(\"$path\") and wait for " +
                "completion before WasmlineLoader.load()."
            return false
        }

        return runCatching { WebWasmPlugin(bytes) }.fold(
            onSuccess = { plugin ->
                failures.remove(path)
                modules[moduleKey] = plugin
                true
            },
            onFailure = { throwable ->
                failures[path] = throwable.message ?: throwable.toString()
                false
            },
        )
    }

    fun require(moduleKey: String): WebWasmPlugin = checkNotNull(modules[moduleKey]) {
        "Wasmline web module '$moduleKey' is not loaded."
    }

    fun remove(moduleKey: String) {
        modules.remove(moduleKey)?.close()
    }

    fun clear() {
        modules.values.forEach { it.close() }
        modules.clear()
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
