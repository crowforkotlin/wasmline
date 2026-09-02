package crow.wasmline.internal.runtime

/**
 * Serializes JVM and Android host runtime state with the JVM monitor.
 *
 * Date: 2026-09-02
 * Author: crowforkotlin
 */
internal actual class WasmlineRuntimeLock {
    actual fun <T> withLock(block: () -> T): T = synchronized(this, block)
}
