package crow.wasmline.loader

import crow.wasmline.WasmlineConfig
import crow.wasmline.loader.model.WasmlineManifestLimits
import crow.wasmline.loader.network.WasmlineNetworkClient

/**
 * Configuration for one load operation.
 *
 * Runtime behavior belongs in [runtimeConfig]. Network, cache, signature, and
 * manifest freshness policies remain loader concerns so the core runtime does
 * not acquire a mandatory HTTP or filesystem dependency.
 *
 * A remote package can load without [networkClient] when its fresh manifest and
 * selected artifact are already in [cache]. A cache miss requires a client or a
 * custom resolver. [maxCacheBytes] bounds the loader's built-in file cache; a
 * custom [cache] remains responsible for its own capacity policy.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
data class WasmlineLoadOptions(
    val runtimeConfig: WasmlineConfig = WasmlineConfig(),
    val networkClient: WasmlineNetworkClient? = null,
    val trustedKeys: WasmlineTrustedKeys? = null,
    val cache: WasmlineCache? = null,
    val manifestTtlMs: Long = DEFAULT_MANIFEST_TTL_MS,
    val maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES,
    val maxArtifactBytes: Long = DEFAULT_MAX_ARTIFACT_BYTES,
    val manifestLimits: WasmlineManifestLimits = WasmlineManifestLimits(),
) {
    init {
        require(manifestTtlMs >= 0) { "manifestTtlMs must be non-negative" }
        require(maxCacheBytes > 0) { "maxCacheBytes must be positive" }
        require(maxArtifactBytes > 0) { "maxArtifactBytes must be positive" }
    }

    public companion object {
        const val DEFAULT_MANIFEST_TTL_MS: Long = 3_600_000L
        const val DEFAULT_MAX_CACHE_BYTES: Long = 512L * 1024L * 1024L
        const val DEFAULT_MAX_ARTIFACT_BYTES: Long = 512L * 1024L * 1024L
    }
}
