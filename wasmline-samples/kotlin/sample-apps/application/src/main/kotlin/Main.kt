@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline.sample.application

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Polymorphic
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf


fun main() {
     runApplicationSample()
}

