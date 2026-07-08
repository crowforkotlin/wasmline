@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package crow.wasmline.internal.bridge

import crow.wasmline.serialization.WasmlineSerializationFactory
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder.Companion.DECODE_DONE
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.serializer

@PublishedApi
internal inline fun <reified T> encodeGeneratedValue(
    factory: WasmlineSerializationFactory,
    value: T,
): ByteArray {
    return factory.encode(serializer<T>(), value)
}

@PublishedApi
internal inline fun <reified T> decodeGeneratedValue(
    factory: WasmlineSerializationFactory,
    payload: ByteArray,
): T {
    return factory.decode(serializer<T>(), payload)
}

/**
 * Serializer that encodes multiple positional parameters through a shared descriptor
 * and per-element serializers. Zero per-method class generation.
 */
private class ArrayBackedSerializer(
    override val descriptor: SerialDescriptor,
    private val serializers: Array<KSerializer<*>>,
) : KSerializer<Array<Any?>> {

    override fun serialize(encoder: Encoder, value: Array<Any?>) {
        encoder.encodeStructure(descriptor) {
            for (i in serializers.indices) {
                @Suppress("UNCHECKED_CAST")
                encodeSerializableElement(descriptor, i, serializers[i] as KSerializer<Any?>, value[i])
            }
        }
    }

    override fun deserialize(decoder: Decoder): Array<Any?> {
        return decoder.decodeStructure(descriptor) {
            val result = arrayOfNulls<Any>(serializers.size)
            while (true) {
                val index = decodeElementIndex(descriptor)
                if (index == DECODE_DONE) break
                result[index] = decodeSerializableElement(descriptor, index, serializers[index])
            }
            result
        }
    }
}

/**
 * Build a positional-params descriptor from a serializers array.
 * Call once at bridge init and cache the result.
 */
@PublishedApi
internal fun buildParamsDescriptor(
    name: String,
    serializers: Array<KSerializer<*>>,
): SerialDescriptor {
    return buildClassSerialDescriptor(name) {
        for (i in serializers.indices) {
            element("p$i", serializers[i].descriptor)
        }
    }
}

/**
 * Encode multiple parameters into a single ByteArray through the shared serializer.
 */
@PublishedApi
internal fun encodeMultiParams(
    factory: WasmlineSerializationFactory,
    descriptor: SerialDescriptor,
    serializers: Array<KSerializer<*>>,
    values: Array<Any?>,
): ByteArray {
    return factory.encode(ArrayBackedSerializer(descriptor, serializers), values)
}

/**
 * Decode a ByteArray into an Array of positional parameters.
 */
@PublishedApi
internal fun decodeMultiParams(
    factory: WasmlineSerializationFactory,
    descriptor: SerialDescriptor,
    serializers: Array<KSerializer<*>>,
    payload: ByteArray,
): Array<Any?> {
    return factory.decode(ArrayBackedSerializer(descriptor, serializers), payload)
}

/**
 * Retrieve and cast a single parameter from a decoded multi-param array.
 * Generated dispatcher code calls this once per parameter to avoid
 * IR-level array access and explicit cast nodes.
 */
@PublishedApi
@Suppress("UNCHECKED_CAST")
internal inline fun <reified T> getMultiParam(args: Array<Any?>, index: Int): T {
    return args[index] as T
}
