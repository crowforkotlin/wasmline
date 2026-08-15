package crow.wasmline.gradle

/** Package variant built before `wasmlineServerDeploy` starts. */
public enum class WasmlineBuildVariant(internal val assembleTaskName: String, internal val compilationName: String) {
    /** Uses the Kotlin/Wasm Development compilation output. */
    DEBUG("wasmlineAssembleDebug", "Development"),

    /** Uses the Kotlin/Wasm Production compilation output. */
    RELEASE("wasmlineAssembleRelease", "Production"),
}
