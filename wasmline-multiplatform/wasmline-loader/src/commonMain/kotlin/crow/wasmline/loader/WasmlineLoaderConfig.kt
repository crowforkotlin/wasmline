package crow.wasmline.loader

/**
 * Loader-specific configuration for remote package resolution.
 *
 * Groups network transport, signature verification, and caching concerns
 * that are specific to the loader layer and not part of the core runtime
 * [crow.wasmline.WasmlineConfig].
 *
 * @property networkClient HTTP transport for resolving [WasmlineSource.RemotePackageUrl].
 *                         When non-null and no custom resolver is configured, the loader
 *                         automatically delegates to the built-in remote resolution pipeline.
 * @property trustedKeys   Trusted public keys for manifest signature verification.
 *                         When non-null, the loader verifies the manifest signature before
 *                         accepting a package. When null, verification is skipped (permissive mode).
 * @property cache         Cache override for downloaded manifests and artifacts.
 *                         When null, a platform-default file-system cache is used for host
 *                         platforms, and [WasmlineNoOpCache] is used for browser platforms.
 */
data class WasmlineLoaderConfig(
    val networkClient: WasmlineNetworkClient? = null,
    val trustedKeys: WasmlineTrustedKeys? = null,
    val cache: WasmlineCache? = null,
)
