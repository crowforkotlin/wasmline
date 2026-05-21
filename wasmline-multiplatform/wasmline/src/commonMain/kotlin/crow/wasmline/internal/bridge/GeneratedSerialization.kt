@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package crow.wasmline.internal.bridge

import crow.wasmline.serialization.WasmlineSerializationFactory
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
