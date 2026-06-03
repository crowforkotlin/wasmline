package crow.wasmline.loader

import crow.wasmline.WasmlineConfig

/**
 * Load request describing what to load and how.
 *
 * @property source The source to load from (local file, local package, or remote URL).
 * @property config Unified configuration for runtime and loading behavior.
 * @property metadata Arbitrary key-value pairs passed through the resolution chain.
 * @property resolvers Optional custom resolver hooks for package sources.
 */
data class WasmlineLoadRequest(
    val source: WasmlineSource,
    val config: WasmlineConfig = WasmlineConfig(),
    val metadata: Map<String, String> = emptyMap(),
    val resolvers: WasmlineSourceResolvers = WasmlineSourceResolvers(),
)
