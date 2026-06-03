@file:OptIn(ExperimentalForeignApi::class)

package crow.wasmline.loader.internal

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

internal actual fun defaultCacheDirectory(): String? {
    return runCatching {
        val paths = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
        val cacheDir = paths.firstOrNull() as? String ?: return null
        "$cacheDir/wasmline"
    }.getOrNull()
}
