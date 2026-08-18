package crow.wasmline

/** Immutable native runtime identity used before serialized artifact selection. */
data class WasmlineNativeRuntimeInfo(
    val backend: WasmlineNativeBackend,
    val wasmtimeVersion: String,
    val targetOs: String? = null,
    val targetCpu: String? = null,
    val is64Bit: Boolean? = null,
) {
    /** Engines this linked native runtime can create. */
    val supportedEngines: Set<WasmlineEngineKind>
        get() = when (backend) {
            WasmlineNativeBackend.CRANELIFT -> setOf(WasmlineEngineKind.CRANELIFT, WasmlineEngineKind.PULLEY)
            WasmlineNativeBackend.PULLEY -> setOf(WasmlineEngineKind.PULLEY)
        }
}
