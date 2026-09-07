package crow.wasmline.loader

import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineCoreLoadResult
import crow.wasmline.WasmlineLoadFailure
import crow.wasmline.WasmlineLoadResult
import crow.wasmline.WasmlineLoadStage
import crow.wasmline.WasmlineLoadState
import crow.wasmline.invocation.WasmlineErrorCode

/**
 * Primary entry point for resolving and loading Wasmline modules on the host side.
 *
 * Lifecycle:
 * ```kotlin
 * suspend fun main() {
 *     val result = WasmlineLoader.load(
 *         descriptor = artifactDescriptor,
 *     )
 *
 *     when (result) {
 *         is WasmlineLoadResult.Success -> result.wasmline.use { it.bind(...) }
 *         is WasmlineLoadResult.Failure -> println(result.failure.message)
 *     }
 * }
 * ```
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
object WasmlineLoader {

    /**
     * Loads a `CORE_WASM + RAW_EXPORT` module without instantiating it.
     *
     * Imports must be registered by the caller through `CoreWasmModule`
     * before [crow.wasmline.CoreWasmModule.instantiate] is called.
     */
    suspend fun loadCoreModule(source: WasmlineSource, options: WasmlineLoadOptions = WasmlineLoadOptions()): WasmlineCoreLoadResult {
        val loaded = load(source, options)
        return when (loaded) {
            is WasmlineLoadResult.Failure -> WasmlineCoreLoadResult.Failure(loaded.failure)

            is WasmlineLoadResult.Success -> {
                val handle = loaded.wasmline
                when (val module = handle.asCoreWasmModule()) {
                    is crow.wasmline.invocation.WasmlineCallResult.Failure -> {
                        handle.close()
                        WasmlineCoreLoadResult.Failure(
                            WasmlineLoadFailure(
                                stage = WasmlineLoadStage.MODULE_CREATION,
                                code = module.failure.code,
                                message = module.failure.message,
                                details = module.failure.details,
                                rawCode = module.failure.rawCode,
                            ),
                        )
                    }

                    is crow.wasmline.invocation.WasmlineCallResult.Success -> WasmlineCoreLoadResult.Success(module.value)
                }
            }
        }
    }

    /**
     * Loads a caller-trusted Core Wasm raw-export descriptor without instantiation.
     * @param descriptor Descriptor identifying the raw Core Wasm artifact.
     * @param options Runtime and loader configuration for this operation.
     */
    suspend fun loadCoreModule(
        descriptor: WasmlineArtifactDescriptor,
        options: WasmlineLoadOptions = WasmlineLoadOptions(),
    ): WasmlineCoreLoadResult = loadCoreModule(
        source = WasmlineSource.LocalArtifactPath(path = descriptor.path, descriptor = descriptor),
        options = options,
    )

    /** Loads a caller-trusted raw Core Wasm artifact path without instantiating it. */
    suspend fun loadCoreModule(path: String, options: WasmlineLoadOptions = WasmlineLoadOptions()): WasmlineCoreLoadResult = loadCoreModule(
        source = WasmlineSource.LocalArtifactPath(path = path),
        options = options,
    )

    /**
     * Load a Wasmline module from the given source.
     *
     * [WasmlineSource.LocalArtifactPath] is caller-trusted direct input. Package
     * sources use the built-in verified package pipeline. Use the request
     * overload when the caller supplies a custom resolver.
     *
     * @param source Where to load from (local file, local package, or remote URL).
     * @param options Runtime and loader configuration for this operation.
     * @return [WasmlineLoadResult.Success] with a [crow.wasmline.Wasmline] instance,
     * or [WasmlineLoadResult.Failure].
     */
    suspend fun load(source: WasmlineSource, options: WasmlineLoadOptions = WasmlineLoadOptions()): WasmlineLoadResult =
        load(WasmlineLoadRequest(source = source, options = options))

    /**
     * Load a module using the complete request API.
     *
     * This overload exposes metadata and custom resolver hooks in addition to
     * the standard runtime, network, cache, and trust options.
     */
    suspend fun load(request: WasmlineLoadRequest): WasmlineLoadResult = DefaultWasmlineLoader.load(request).toResult()

    /**
     * Load a direct caller-trusted artifact with an explicit execution model and
     * invocation protocol.
     *
     * This overload does not parse or verify a package manifest. Native AOT
     * format and compatibility validation still applies.
     */
    suspend fun load(descriptor: WasmlineArtifactDescriptor, options: WasmlineLoadOptions = WasmlineLoadOptions()): WasmlineLoadResult =
        load(
            source = WasmlineSource.LocalArtifactPath(path = descriptor.path, descriptor = descriptor),
            options = options,
        )

    /**
     * Load a Wasmline module by auto-detecting the source type from the input string.
     *
     * - Starts with `http://` or `https://` → [WasmlineSource.RemoteManifestUrl]
     * - Ends with `.pwasm`, `.cwasm`, or `.wasm` → caller-trusted
     *   [WasmlineSource.LocalArtifactPath]
     * - Otherwise → [WasmlineSource.LocalManifestPath] through the built-in
     *   verified package pipeline
     *
     * Suffix detection is not a trust decision. Pass a direct artifact path
     * only when the caller has already trusted it out of band; use a manifest
     * path or URL for signed package loading.
     */
    suspend fun load(source: String, options: WasmlineLoadOptions = WasmlineLoadOptions()): WasmlineLoadResult {
        val input = source.trim()
        val wasmlineSource = when {
            input.startsWith(prefix = "http://") || input.startsWith("https://") ->
                WasmlineSource.RemoteManifestUrl(url = input)

            input.endsWith(".pwasm") || input.endsWith(".cwasm") || input.endsWith(".wasm") ->
                WasmlineSource.LocalArtifactPath(path = input)

            else ->
                WasmlineSource.LocalManifestPath(path = input)
        }
        return load(source = wasmlineSource, options = options)
    }
}

/** Converts internal [WasmlineLoadState] to public [WasmlineLoadResult]. */
private fun WasmlineLoadState.toResult(): WasmlineLoadResult = when (this) {
    is WasmlineLoadState.Success -> WasmlineLoadResult.Success(wasmline = this.wasmline)
    is WasmlineLoadState.Failure -> WasmlineLoadResult.Failure(failure = this.failure)
}
