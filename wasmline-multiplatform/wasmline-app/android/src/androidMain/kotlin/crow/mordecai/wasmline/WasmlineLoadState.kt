package crow.mordecai.wasmline

sealed class WasmlineLoadState() {
    companion object Companion {
        const val CODE_SUCCESS_JIT: Byte = 0
        const val CODE_SUCCESS_AOT: Byte = 1
        const val CODE_FAILURE: Byte = 2
    }

    data class Success(val code: Byte, val wasmLine: Wasmline) : WasmlineLoadState()
    data class Failure(val code: Byte, val cause: String) : WasmlineLoadState()
}