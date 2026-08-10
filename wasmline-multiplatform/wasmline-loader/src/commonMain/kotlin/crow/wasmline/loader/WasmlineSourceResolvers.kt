package crow.wasmline.loader

/**
 * Optional custom resolver hooks for non-artifact load sources.
 *
 * Callers can provide custom resolution logic for [WasmlineSource.LocalManifestPath]
 * and [WasmlineSource.RemoteManifestUrl] sources. When a resolver is null, the
 * default built-in resolution is used (if available).
 *
 * A custom resolver is an explicit caller-owned trust boundary. Returning
 * [WasmlineSource.LocalArtifactPath] from [WasmlineSourceResolution.ContinueWith]
 * requests a direct caller-trusted load; it does not inherit manifest signature
 * or SHA-256 verification. Leave the resolver null to use the built-in signed
 * package pipeline.
 */
data class WasmlineSourceResolvers(
    val localPackage: WasmlineLocalPackageResolver? = null,
    val remotePackage: WasmlineRemotePackageResolver? = null,
)

/**
 * Custom resolver for local package sources.
 *
 * The resolver owns verification for any artifact it returns as a direct local
 * source.
 */
fun interface WasmlineLocalPackageResolver {
    fun resolve(source: WasmlineSource.LocalManifestPath, request: WasmlineLoadRequest): WasmlineSourceResolution
}

/**
 * Custom resolver for remote package sources.
 *
 * The resolver owns verification for any artifact it returns as a direct local
 * source.
 */
fun interface WasmlineRemotePackageResolver {
    fun resolve(source: WasmlineSource.RemoteManifestUrl, request: WasmlineLoadRequest): WasmlineSourceResolution
}
