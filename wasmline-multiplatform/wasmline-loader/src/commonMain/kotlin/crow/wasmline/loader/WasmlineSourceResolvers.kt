package crow.wasmline.loader

/**
 * Optional custom resolver hooks for non-artifact load sources.
 *
 * Callers can provide custom resolution logic for [WasmlineSource.LocalManifestPath]
 * and [WasmlineSource.RemoteManifestUrl] sources. When a resolver is null, the
 * default built-in resolution is used (if available).
 */
data class WasmlineSourceResolvers(
    val localPackage: WasmlineLocalPackageResolver? = null,
    val remotePackage: WasmlineRemotePackageResolver? = null,
)

/**
 * Custom resolver for local package sources.
 */
fun interface WasmlineLocalPackageResolver {
    fun resolve(source: WasmlineSource.LocalManifestPath, request: WasmlineLoadRequest): WasmlineSourceResolution
}

/**
 * Custom resolver for remote package sources.
 */
fun interface WasmlineRemotePackageResolver {
    fun resolve(source: WasmlineSource.RemoteManifestUrl, request: WasmlineLoadRequest): WasmlineSourceResolution
}
