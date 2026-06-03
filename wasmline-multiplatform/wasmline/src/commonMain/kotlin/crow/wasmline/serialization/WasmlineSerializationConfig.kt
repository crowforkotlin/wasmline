package crow.wasmline.serialization

import kotlinx.serialization.Serializable

/**
 * Host-side serialization selection attached to one loaded Wasmline module.
 *
 * This value is only stored on the host/runtime side. Plugin-side code configures
 * its local factory through `Wasmline.get()`.
 */
@Serializable
data class WasmlineSerializationConfig(
    val factoryId: String,
    val options: Map<String, String> = emptyMap(),
) {
    companion object {
        fun rawBytes(
            options: Map<String, String> = emptyMap(),
        ): WasmlineSerializationConfig = WasmlineSerializationConfig(
            factoryId = WasmlineRawBytesSerializationFactory.id,
            options = options,
        )

        fun protobuf(
            options: Map<String, String> = emptyMap(),
        ): WasmlineSerializationConfig = WasmlineSerializationConfig(
            factoryId = WasmlineProtobufSerializationFactory.id,
            options = options,
        )

        fun custom(
            factoryId: String,
            options: Map<String, String> = emptyMap(),
        ): WasmlineSerializationConfig = WasmlineSerializationConfig(
            factoryId = factoryId,
            options = options,
        )
    }
}
