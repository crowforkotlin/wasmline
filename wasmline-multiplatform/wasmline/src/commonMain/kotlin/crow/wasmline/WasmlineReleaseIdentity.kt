package crow.wasmline

/**
 * Defines the Kotlin runtime identity that must match every linked native engine.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
internal object WasmlineReleaseIdentity {
    const val RELEASE_VERSION: String = "1.0.0"
    const val NATIVE_BRIDGE_ABI_VERSION: Int = 1
}
