@file:OptIn(ExperimentalContracts::class)

package crow.mordecai.wasmline

import kotlin.contracts.ExperimentalContracts

/**
 * Wasmline load module state
 *
 * 2026-01-02 19:31:34 周五 下午
 * @author crowforkotlin
 */
sealed class WasmlineLoadState {
    companion object Companion {
        const val CODE_SUCCESS_JIT: Byte = 0
        const val CODE_SUCCESS_AOT: Byte = 1
        const val CODE_FAILURE: Byte = 2
    }

    data class Success(val code: Byte, val wasmline: Wasmline) : WasmlineLoadState()
    data class Failure(val code: Byte, val cause: String) : WasmlineLoadState()
}

inline fun WasmlineLoadState.onSuccess(block: WasmlineLoadState.Success.() -> Unit): WasmlineLoadState {
    if (this is WasmlineLoadState.Success) block()
    return this
}
inline fun WasmlineLoadState.onFailure(block: WasmlineLoadState.Failure.() -> Unit): WasmlineLoadState {
    if (this is WasmlineLoadState.Failure) block()
    return this
}