package crow.wasmline

import kotlinx.serialization.Serializable

/**
 * Identifies a Wasmtime artifact backend and execution engine.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@Serializable
enum class WasmlineEngineKind {
    PULLEY,
    CRANELIFT,
}
