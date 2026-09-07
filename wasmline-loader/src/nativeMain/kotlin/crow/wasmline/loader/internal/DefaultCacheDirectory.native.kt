@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package crow.wasmline.loader.internal

import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Resolves a conventional per-user cache directory without platform SDK APIs.
 *
 * Author: crowforkotlin
 * Date: 2026-08-19
 */
internal actual fun defaultCacheDirectory(): String? {
    val explicit = getenv("WASMLINE_CACHE_DIR")?.toKString()?.takeIf(String::isNotBlank)
    if (explicit != null) return explicit
    val localAppData = getenv("LOCALAPPDATA")?.toKString()?.takeIf(String::isNotBlank)
    if (localAppData != null) return "$localAppData/wasmline"
    val xdg = getenv("XDG_CACHE_HOME")?.toKString()?.takeIf(String::isNotBlank)
    if (xdg != null) return "$xdg/wasmline"
    val home = getenv("HOME")?.toKString()?.takeIf(String::isNotBlank) ?: return null
    return "$home/.cache/wasmline"
}
