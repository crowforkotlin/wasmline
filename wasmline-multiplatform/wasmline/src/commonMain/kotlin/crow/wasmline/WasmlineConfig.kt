package crow.wasmline

import crow.wasmline.serialization.WasmlineSerializationConfig

/**
 * Host-side runtime configuration for one loaded Wasmline module.
 *
 * Keep host configuration extensible here so future runtime switches do not need
 * to keep expanding `load(...)` or the Wasmline constructor surface directly.
 */
data class WasmlineConfig(
    val serialization: WasmlineSerializationConfig = WasmlineSerializationConfig.protobuf(),
)
