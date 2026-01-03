package crow.wasmtime.wasmline.model

import kotlinx.serialization.Serializable

@Serializable
data class WasmError(val message: String)
