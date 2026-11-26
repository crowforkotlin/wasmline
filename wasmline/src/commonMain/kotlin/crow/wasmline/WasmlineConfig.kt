package crow.wasmline

import crow.wasmline.serialization.WasmlineSerializationConfig

/**
 * Runtime configuration for a Wasmline module.
 *
 * @property serialization Serialization format for bridge communication.
 * @property supportConcurrent Whether concurrent loads of the same artifact wait for one another.
 *           When `true`, the loader waits for an in-flight load and reuses its result.
 *           When `false` (default), the cache remains synchronized, but two threads may deserialize
 *           the same artifact and the later result is discarded.
 */
data class WasmlineConfig(
    val serialization: WasmlineSerializationConfig = WasmlineSerializationConfig.protobuf(),
    val supportConcurrent: Boolean = false,
)
