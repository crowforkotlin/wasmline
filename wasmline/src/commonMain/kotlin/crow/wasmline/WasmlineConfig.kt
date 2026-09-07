package crow.wasmline

import crow.wasmline.serialization.WasmlineSerializationConfig

/**
 * Runtime configuration for a Wasmline module.
 *
 * @property serialization Serialization format for bridge communication.
 * @property supportConcurrent Whether the loading path should support concurrent access.
 *           When `true`, the runtime enables internal mutex for thread-safe loading.
 *           When `false` (default), the loading path is lock-free.
 */
data class WasmlineConfig(
    val serialization: WasmlineSerializationConfig = WasmlineSerializationConfig.protobuf(),
    val supportConcurrent: Boolean = false,
)
