package crow.wasmline.loader

/**
 * Host-facing load request owned by the loader module.
 *
 * Runtime modules only understand prepared local precompiled artifacts, while
 * this request model reserves space for richer package/remote workflows.
 *
 * 2026-04-08
 * @author crowforkotlin
 */
data class WasmlineLoadRequest(
    val source: WasmlineSource,
    val threadSafe: Boolean = false,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Source description for future Wasmline loading workflows.
 *
 * Only [LocalArtifactFile] is executable today. Other variants intentionally
 * exist now so Host-facing APIs stop baking file-path-only assumptions into
 * the runtime layer.
 *
 * 2026-04-08
 * @author crowforkotlin
 */
sealed interface WasmlineSource {
    data class LocalArtifactFile(val path: String) : WasmlineSource
    data class LocalPackageFile(val path: String) : WasmlineSource
    data class RemotePackageUrl(val url: String) : WasmlineSource
}

