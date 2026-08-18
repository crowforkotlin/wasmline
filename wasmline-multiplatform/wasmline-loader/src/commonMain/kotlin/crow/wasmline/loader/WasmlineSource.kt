package crow.wasmline.loader

import crow.wasmline.WasmlineArtifactDescriptor

/**
 * Describes where to load a Wasmline module from.
 *
 * Trust boundary:
 *
 * - [LocalArtifactPath] is a direct, caller-trusted artifact path. It does not
 *   imply manifest signature or package SHA-256 verification.
 * - [LocalManifestPath] and [RemoteManifestUrl] are package sources. Their
 *   built-in resolvers verify a manifest signature before using its artifact
 *   metadata, then verify the selected artifact hash.
 * - Custom source resolvers are caller-owned trust boundaries. See
 *   [WasmlineSourceResolvers] before returning a direct artifact path from one.
 */
sealed interface WasmlineSource {
    /**
     * Direct artifact input trusted by the caller out of band.
     *
     * Native AOT format and compatibility validation still applies, but this
     * source bypasses package manifest signature and SHA-256 verification.
     */
    data class LocalArtifactPath(val path: String, val descriptor: WasmlineArtifactDescriptor? = null) : WasmlineSource

    /** Local signed package manifest resolved by the built-in package pipeline when no custom resolver is supplied. */
    data class LocalManifestPath(val path: String) : WasmlineSource

    /** Remote signed package manifest resolved by the built-in package pipeline when no custom resolver is supplied. */
    data class RemoteManifestUrl(val url: String) : WasmlineSource
}

/** Internal handoff emitted only after built-in package manifest and artifact verification. */
internal data class VerifiedPackageArtifact(val descriptor: WasmlineArtifactDescriptor) : WasmlineSource
