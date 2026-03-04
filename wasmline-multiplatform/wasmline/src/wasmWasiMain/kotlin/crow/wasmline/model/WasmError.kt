package crow.wasmline.model

import kotlinx.serialization.Serializable

@Serializable
data class WasmError(val message: String)
