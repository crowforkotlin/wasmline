package crow.wasmline.loader

/**
 * Load request describing what to load and how.
 *
 * @property source The source to load from (local file, local package, or remote URL).
 * @property options Runtime and loader configuration for this operation.
 * @property metadata Arbitrary key-value pairs passed through the resolution chain.
 * @property resolvers Optional custom resolver hooks for package sources.
 */
data class WasmlineLoadRequest(
    val source: WasmlineSource,
    val options: WasmlineLoadOptions = WasmlineLoadOptions(),
    val metadata: Map<String, String> = emptyMap(),
    val resolvers: WasmlineSourceResolvers = WasmlineSourceResolvers(),
)
