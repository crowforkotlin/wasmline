package crow.wasmline.loader

import crow.wasmline.WasmlineArtifactDescriptor

/**
 * Describes where to load a Wasmline module from.
 *
 * Trust boundary:
 *
 * - [LocalArtifactPath] is a direct artifact path. It never re-runs manifest
 *   signature or package SHA-256 verification, so a caller that constructs it
 *   owns artifact trust. The built-in package resolvers also return it after
 *   they have verified the manifest signature and selected artifact hash.
 * - [LocalManifestPath] and [RemoteManifestUrl] are package sources. Their
 *   built-in resolvers verify a manifest signature before using its artifact
 *   metadata, then verify the selected artifact hash.
 * - Custom source resolvers are caller-owned trust boundaries. See
 *   [WasmlineSourceResolvers] before returning a direct artifact path from one.
 */
sealed interface WasmlineSource {
    /**
     * Direct artifact input whose trust is owned by the caller.
     *
     * The loader loads the path after descriptor and native AOT format
     * validation but performs no package manifest signature or artifact
     * SHA-256 check for this source. Built-in package resolvers emit this
     * variant after they have already verified the manifest signature and the
     * selected artifact hash.
     */
    data class LocalArtifactPath(val path: String, val descriptor: WasmlineArtifactDescriptor? = null) : WasmlineSource

    /** Local signed package manifest resolved by the built-in package pipeline when no custom resolver is supplied. */
    data class LocalManifestPath(val path: String) : WasmlineSource

    /** Remote signed package manifest resolved by the built-in package pipeline when no custom resolver is supplied. */
    data class RemoteManifestUrl(val url: String) : WasmlineSource
}
