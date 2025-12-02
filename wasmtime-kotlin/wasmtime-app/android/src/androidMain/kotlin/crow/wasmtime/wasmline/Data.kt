package crow.wasmtime.wasmline

import kotlinx.serialization.Serializable

@Serializable
data class Data(
    val id: Long,
    val name: String,
    val key: String
)
