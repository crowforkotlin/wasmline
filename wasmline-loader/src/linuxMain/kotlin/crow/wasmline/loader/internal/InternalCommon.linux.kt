@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package crow.wasmline.loader.internal

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.timespec

internal actual val systemEpochMsClock: () -> Long = ::currentLinuxEpochMs

private fun currentLinuxEpochMs(): Long = memScoped {
    val value = alloc<timespec>()
    check(clock_gettime(CLOCK_REALTIME, value.ptr) == 0) {
        "Unable to read the Linux Native wall clock."
    }
    value.tv_sec * 1000L + value.tv_nsec / 1_000_000L
}
