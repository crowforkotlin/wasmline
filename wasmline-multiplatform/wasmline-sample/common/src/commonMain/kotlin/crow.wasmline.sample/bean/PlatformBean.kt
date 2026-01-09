package crow.wasmline.sample.bean

import kotlinx.serialization.Serializable

@Serializable
data class PlatformBean(
    val platform: String,
    val content: String,
    val timeStr: String,
    val timeMs: Long,
)
