@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package crow.wasmline.internal.runtime

import crow.wasmline.native.c.wasmline_lock_acquire
import crow.wasmline.native.c.wasmline_lock_create
import crow.wasmline.native.c.wasmline_lock_destroy
import crow.wasmline.native.c.wasmline_lock_release
import kotlinx.cinterop.COpaquePointer
import kotlin.native.ref.createCleaner

/**
 * Serializes host runtime state per lock instance with a native recursive mutex.
 *
 * Each constructed lock owns its own mutex, so two independent instances never
 * wait on each other. A same-thread callback that re-enters one instance is
 * allowed by the recursive mutex and is rejected by the native session instead.
 *
 * Date: 2026-09-02
 * Author: crowforkotlin
 */
internal actual class WasmlineRuntimeLock {
    private val handle: COpaquePointer? = wasmline_lock_create()

    @Suppress("unused")
    private val cleaner = createCleaner(handle) { pointer -> wasmline_lock_destroy(pointer) }

    actual fun <T> withLock(block: () -> T): T {
        wasmline_lock_acquire(handle)
        return try {
            block()
        } finally {
            wasmline_lock_release(handle)
        }
    }
}
