package crow.wasmline

/** Identifies a Wasmtime execution engine that the native runtime can create. */
enum class WasmlineEngineKind {
    PULLEY,
    CRANELIFT,
}
