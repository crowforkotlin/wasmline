package crow.wasmline

/** Identifies the native engine variant selected by the linked Wasmline runtime. */
enum class WasmlineNativeBackend {
    CRANELIFT,
    PULLEY,
}
