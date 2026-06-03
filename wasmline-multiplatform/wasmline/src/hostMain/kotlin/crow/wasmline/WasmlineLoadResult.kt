package crow.wasmline

/**
 * Public result of a Wasmline load operation.
 *
 * - [Success]: Module loaded successfully, contains a [Wasmline] instance for communication.
 * - [Failure]: Loading failed, contains a descriptive error message.
 */
sealed interface WasmlineLoadResult {
    data class Success(val wasmline: Wasmline) : WasmlineLoadResult
    data class Failure(val cause: String) : WasmlineLoadResult
}
