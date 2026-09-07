@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package crow.wasmline.internal.runtime

import crow.wasmline.native.c.wasmline_lock
import crow.wasmline.native.c.wasmline_unlock

/**
 * Serializes Kotlin/Native host runtime state with the native recursive lock.
 *
 * Date: 2026-09-02
 * Author: crowforkotlin
 */
internal actual class WasmlineRuntimeLock {
    actual fun <T> withLock(block: () -> T): T {
        wasmline_lock()
        return try {
            block()
        } finally {
            wasmline_unlock()
        }
    }
}
