package crow.mordecai.wasmline.extensions

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

@Keep
@Serializable
data class Data(
    val id: Long,
    val name: String,
    val key: String
)
