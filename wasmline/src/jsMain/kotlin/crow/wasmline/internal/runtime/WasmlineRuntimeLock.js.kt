package crow.wasmline.internal.runtime

/**
 * Executes a critical section on the single-threaded JavaScript host.
 *
 * Date: 2026-09-02
 * Author: crowforkotlin
 */
internal actual class WasmlineRuntimeLock {
    actual fun <T> withLock(block: () -> T): T = block()
}
