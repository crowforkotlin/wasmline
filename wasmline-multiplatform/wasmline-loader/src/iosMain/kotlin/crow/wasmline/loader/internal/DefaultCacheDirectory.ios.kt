@file:OptIn(ExperimentalForeignApi::class)

package crow.wasmline.loader.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringUsingEncoding
import platform.Foundation.urlsForDirectory

internal actual fun defaultCacheDirectory(): String? {
    return runCatching {
        val urls = NSFileManager.defaultManager.urlsForDirectory(NSSCachesDirectory, NSUserDomainMask)
        val cacheUrl = urls.firstOrNull() ?: return null
        val wasmlineUrl = cacheUrl.URLByAppendingPathComponent("wasmline") ?: return null
        wasmlineUrl.path
    }.getOrNull()
}
