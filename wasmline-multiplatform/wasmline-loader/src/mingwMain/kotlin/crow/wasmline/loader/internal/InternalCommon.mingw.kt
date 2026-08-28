@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package crow.wasmline.loader.internal

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.windows.FILETIME
import platform.windows.GetSystemTimeAsFileTime

internal actual val systemEpochMsClock: () -> Long = ::currentMingwEpochMs

private fun currentMingwEpochMs(): Long = memScoped {
    val fileTime = alloc<FILETIME>()
    GetSystemTimeAsFileTime(fileTime.ptr)
    val high = fileTime.dwHighDateTime.toLong() and 0xFFFF_FFFFL
    val low = fileTime.dwLowDateTime.toLong() and 0xFFFF_FFFFL
    ((high shl 32) or low) / 10_000L - 11_644_473_600_000L
}
