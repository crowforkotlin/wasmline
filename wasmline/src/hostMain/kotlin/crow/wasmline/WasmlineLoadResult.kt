package crow.wasmline

/**
 * Represents the result of loading an uninstantiated Core Wasm module.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
sealed interface WasmlineCoreLoadResult {
    /**
     * Carries a loaded, uninstantiated Core Wasm module.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     *
     * @property module Loaded module handle.
     */
    data class Success(val module: CoreWasmModule) : WasmlineCoreLoadResult

    /**
     * Carries a structured Core Wasm load failure.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     *
     * @property failure Failure stage, code, and diagnostics.
     */
    data class Failure(val failure: WasmlineLoadFailure) : WasmlineCoreLoadResult
}

/**
 * Represents the result of loading a Wasmline runtime handle.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
sealed interface WasmlineLoadResult {
    /**
     * Carries a loaded Wasmline handle.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     *
     * @property wasmline Loaded runtime handle.
     */
    data class Success(val wasmline: Wasmline) : WasmlineLoadResult

    /**
     * Carries a structured load failure.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     *
     * @property failure Failure stage, code, and diagnostics.
     */
    data class Failure(val failure: WasmlineLoadFailure) : WasmlineLoadResult
}
