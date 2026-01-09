@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline.sample.extensions

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

val baseProtobuf = ProtoBuf { }
inline fun<reified T> toProtoBytes(value: T): ByteArray { return baseProtobuf.encodeToByteArray(value) }
inline fun<reified T> toProtoBean(bytes: ByteArray): T { return baseProtobuf.decodeFromByteArray(bytes) }
