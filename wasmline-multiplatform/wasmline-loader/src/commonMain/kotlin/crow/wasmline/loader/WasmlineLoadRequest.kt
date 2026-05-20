package crow.wasmline.loader

import crow.wasmline.WasmlineLoadState

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
    val resolvers: WasmlineSourceResolvers = WasmlineSourceResolvers(),
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

/**
 * Host-side resolver hooks for non-artifact load sources.
 *
 * The current runtime can only execute prepared local `.cwasm` / `.pwasm`
 * artifacts, so richer source types must be translated by the loader layer
 * before they reach the runtime bridge.
 */
data class WasmlineSourceResolvers(
    val localPackage: WasmlineLocalPackageResolver? = null,
    val remotePackage: WasmlineRemotePackageResolver? = null,
)

fun interface WasmlineLocalPackageResolver {
    fun resolve(
        source: WasmlineSource.LocalPackageFile,
        request: WasmlineLoadRequest,
    ): WasmlineSourceResolution
}

fun interface WasmlineRemotePackageResolver {
    fun resolve(
        source: WasmlineSource.RemotePackageUrl,
        request: WasmlineLoadRequest,
    ): WasmlineSourceResolution
}

sealed interface WasmlineSourceResolution {
    data class ContinueWith(val source: WasmlineSource) : WasmlineSourceResolution
    data class Complete(val state: WasmlineLoadState) : WasmlineSourceResolution
}
