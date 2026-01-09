@file:OptIn(ExperimentalSerializationApi::class)
@file:Suppress("NOTHING_TO_INLINE")

package crow.mordecai.wasmline.extensions

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

val baseJson = Json {
    isLenient = true
    prettyPrint = true
}


inline fun<reified T> toJsonString(value: T): String { return baseJson.encodeToString(value) }
inline fun <reified T> toJsonBean(json: String) : T { return baseJson.decodeFromString(json) }