package crow.wasmline

import crow.wasmline.serialization.WasmlineSerializationConfig

/**
 * Unified configuration for loading and running a Wasmline module.
 *
 * @property serialization Serialization format for bridge communication.
 * @property supportConcurrent Whether the loading path should support concurrent access.
 *           When `true`, the runtime enables internal mutex for thread-safe loading.
 *           When `false` (default), the loading path is lock-free.
 * @property networkClient HTTP transport for remote package loading. Null means no remote loading.
 * @property trustedKeys Trusted public keys for manifest signature verification.
 *           When null, signature verification is skipped (permissive mode).
 * @property cache Cache for downloaded manifests and artifacts.
 *           When null, a platform-default file-system cache is used.
 */
data class WasmlineConfig(
    val serialization: WasmlineSerializationConfig = WasmlineSerializationConfig.protobuf(),
    val supportConcurrent: Boolean = false,
    val networkClient: WasmlineNetworkClient? = null,
    val trustedKeys: WasmlineTrustedKeys? = null,
    val cache: WasmlineCache? = null,
)
