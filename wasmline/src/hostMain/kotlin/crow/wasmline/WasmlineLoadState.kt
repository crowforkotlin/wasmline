@file:OptIn(ExperimentalContracts::class)

package crow.wasmline

import kotlin.contracts.ExperimentalContracts

/**
 * Represents the internal state produced by a platform module load.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
sealed class WasmlineLoadState {
    /**
     * Defines stable byte codes carried by the platform load bridge.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     */
    companion object Companion {
        /** Successful Pulley AOT load state code. */
        const val CODE_SUCCESS_PULLEY: Byte = 0

        /** Successful Cranelift AOT load state code. */
        const val CODE_SUCCESS_AOT: Byte = 1

        /** Generic failure load state code. */
        const val CODE_FAILURE: Byte = 2

        /** Successful raw WebAssembly load state code. */
        const val CODE_SUCCESS_WASM: Byte = 3

        /** Successful Component Model load state code. */
        const val CODE_SUCCESS_COMPONENT: Byte = 4

        /** Successful raw-export load state code. */
        const val CODE_SUCCESS_RAW_EXPORT: Byte = 5
    }

    /**
     * Carries a successful platform load state.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     *
     * @property code Stable success code.
     * @property wasmline Loaded runtime handle.
     */
    data class Success(val code: Byte, val wasmline: Wasmline) : WasmlineLoadState()

    /**
     * Carries a failed platform load state.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     *
     * @property code Stable failure-state code.
     * @property failure Canonical structured load failure.
     */
    data class Failure(val code: Byte, val failure: WasmlineLoadFailure) : WasmlineLoadState()
}

/** Runs [block] when this state represents a successful load. */
inline fun WasmlineLoadState.onSuccess(block: WasmlineLoadState.Success.() -> Unit): WasmlineLoadState {
    if (this is WasmlineLoadState.Success) block()
    return this
}

/** Runs [block] when this state represents a failed load. */
inline fun WasmlineLoadState.onFailure(block: WasmlineLoadState.Failure.() -> Unit): WasmlineLoadState {
    if (this is WasmlineLoadState.Failure) block()
    return this
}
