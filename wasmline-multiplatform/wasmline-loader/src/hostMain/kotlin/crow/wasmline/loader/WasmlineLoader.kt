package crow.wasmline.loader

import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineLoadResult
import crow.wasmline.WasmlineLoadState
import crow.wasmline.WasmlineWarmupMode
import crow.wasmline.wasmlineBootstrap
import crow.wasmline.wasmlineShutdown
import crow.wasmline.wasmlineWarmup

/**
 * Primary entry point for loading and managing Wasmline modules on the host side.
 *
 * Lifecycle:
 * ```kotlin
 * WasmlineLoader.bootstrap()  // Initialize the runtime engine
 *
 * val result = WasmlineLoader.load(
 *     source = WasmlineSource.LocalArtifactPath("plugin.pwasm"),
 *     config = WasmlineConfig(networkClient = KtorNetworkClient()),
 * )
 *
 * when (result) {
 *     is WasmlineLoadResult.Success -> result.wasmline.use { it.bind(...) }
 *     is WasmlineLoadResult.Failure -> println(result.cause)
 * }
 *
 * WasmlineLoader.shutdown()  // Release the engine
 * ```
 */
object WasmlineLoader {

    /**
     * Initialize the Wasmline runtime engine.
     *
     * On JVM/Android this ensures the native library is loaded.
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    fun bootstrap() {
        wasmlineBootstrap()
    }

    /**
     * Release the global engine and clear cached modules.
     */
    fun shutdown() {
        wasmlineShutdown()
    }

    /**
     * Eagerly warm up the runtime engine for a specific backend.
     */
    fun warmup(mode: WasmlineWarmupMode) {
        wasmlineWarmup(mode)
    }

    /**
     * Load a Wasmline module from the given source.
     *
     * @param source Where to load from (local file, local package, or remote URL).
     * @param config Unified configuration for runtime, network, cache, and trusted keys.
     * @return [WasmlineLoadResult.Success] with a [Wasmline] instance, or [WasmlineLoadResult.Failure].
     */
    fun load(source: WasmlineSource, config: WasmlineConfig = WasmlineConfig()): WasmlineLoadResult {
        val request = WasmlineLoadRequest(source = source, config = config)
        return loadInternal(request).toResult()
    }

    /**
     * Load a Wasmline module by auto-detecting the source type from the input string.
     *
     * - Starts with `http://` or `https://` → [WasmlineSource.RemoteManifestUrl]
     * - Ends with `.pwasm`, `.cwasm`, or `.wasm` → [WasmlineSource.LocalArtifactPath]
     * - Otherwise → [WasmlineSource.LocalManifestPath]
     */
    fun load(source: String, config: WasmlineConfig = WasmlineConfig()): WasmlineLoadResult {
        val input = source.trim()
        val wasmlineSource = when {
            input.startsWith(prefix = "http://") || input.startsWith("https://") ->
                WasmlineSource.RemoteManifestUrl(url = input)
            input.endsWith(".pwasm") || input.endsWith(".cwasm") || input.endsWith(".wasm") ->
                WasmlineSource.LocalArtifactPath(path = input)
            else ->
                WasmlineSource.LocalManifestPath(path = input)
        }
        return load(source = wasmlineSource, config = config)
    }

    private fun loadInternal(request: WasmlineLoadRequest): WasmlineLoadState = DefaultWasmlineLoader.load(request)
}

/**
 * Convert internal [WasmlineLoadState] to public [WasmlineLoadResult].
 */
private fun WasmlineLoadState.toResult(): WasmlineLoadResult = when (this) {
    is WasmlineLoadState.Success -> WasmlineLoadResult.Success(wasmline = this.wasmline)
    is WasmlineLoadState.Failure -> WasmlineLoadResult.Failure(cause = this.cause)
}
