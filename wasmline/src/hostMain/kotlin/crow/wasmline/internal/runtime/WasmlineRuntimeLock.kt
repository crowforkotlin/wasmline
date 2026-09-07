package crow.wasmline.internal.runtime

/**
 * Serializes mutable host runtime state on each supported platform.
 *
 * Date: 2026-09-02
 * Author: crowforkotlin
 */
internal expect class WasmlineRuntimeLock() {
    fun <T> withLock(block: () -> T): T
}
