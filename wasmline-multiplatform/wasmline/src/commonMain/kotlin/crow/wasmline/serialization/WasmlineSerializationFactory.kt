@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package crow.wasmline.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Process-local serialization factory resolved from runtime config.
 *
 * Host and plugin do not exchange this object across the boundary. They only
 * exchange [id] through [WasmlineSerializationConfig].
 */
interface WasmlineSerializationFactory {
    val id: String

    fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray

    fun <T> decode(serializer: KSerializer<T>, payload: ByteArray): T
}

object WasmlineRawBytesSerializationFactory : WasmlineSerializationFactory {
    override val id: String = "raw"

    override fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray = when (serializer.descriptor.serialName) {
        BYTE_ARRAY_SERIAL_NAME ->
            value as? ByteArray
                ?: error("Raw serialization expected ByteArray for ${serializer.descriptor.serialName}.")

        UNIT_SERIAL_NAME -> ByteArray(0)
        else -> unsupportedRawSerialization(serializer)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> decode(serializer: KSerializer<T>, payload: ByteArray): T = when (serializer.descriptor.serialName) {
        BYTE_ARRAY_SERIAL_NAME -> payload as T
        UNIT_SERIAL_NAME -> Unit as T
        else -> unsupportedRawSerialization(serializer)
    }
}

object WasmlineProtobufSerializationFactory : WasmlineSerializationFactory {
    override val id: String = "protobuf"

    override fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray = ProtoBuf.encodeToByteArray(serializer, value)

    override fun <T> decode(serializer: KSerializer<T>, payload: ByteArray): T = ProtoBuf.decodeFromByteArray(serializer, payload)
}

private fun unsupportedRawSerialization(serializer: KSerializer<*>): Nothing {
    error(
        "Raw serialization only supports ByteArray and Unit, but received ${serializer.descriptor.serialName}.",
    )
}

private const val BYTE_ARRAY_SERIAL_NAME = "kotlin.ByteArray"
private const val UNIT_SERIAL_NAME = "kotlin.Unit"
