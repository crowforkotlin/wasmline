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
    /**
     * Whether the loaded module should support concurrent access from
     * multiple threads. When `true`, the runtime uses read/write locks
     * for thread safety. When `false` (default), the module is optimized
     * for single-threaded usage.
     */
    val threadSafe: Boolean = false,
)
