package crow.wasmline.loader

import crow.wasmline.WasmlineConfig
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
 * custom resolver.
 */
data class WasmlineLoadOptions(
    val runtimeConfig: WasmlineConfig = WasmlineConfig(),
    val networkClient: WasmlineNetworkClient? = null,
    val trustedKeys: WasmlineTrustedKeys? = null,
    val cache: WasmlineCache? = null,
    val manifestTtlMs: Long = DEFAULT_MANIFEST_TTL_MS,
) {
    init {
        require(manifestTtlMs >= 0) { "manifestTtlMs must be non-negative" }
    }

    public companion object {
        const val DEFAULT_MANIFEST_TTL_MS: Long = 3_600_000L
    }
}
