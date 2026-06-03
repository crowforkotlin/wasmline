@file:OptIn(ExperimentalForeignApi::class)

package crow.wasmline.loader.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fseek
import platform.posix.fwrite
import platform.posix.memcpy

private val fileManager = NSFileManager.defaultManager

internal actual fun hostPathExists(path: String): Boolean {
    return fileManager.fileExistsAtPath(path)
}

internal actual fun readHostFileBytes(path: String): ByteArray? {
    val data = fileManager.contentsAtPath(path) ?: return null
    val length = data.length.toInt()
    if (length == 0) {
        return ByteArray(0)
    }
    val bytes = data.bytes ?: return null
    return ByteArray(length).apply {
        usePinned { pinned ->
            memcpy(pinned.addressOf(0), bytes, data.length)
        }
    }
}

internal actual fun resolveHostArtifactPath(manifestPath: String, artifactUrl: String): String {
    if (artifactUrl.startsWith("/")) {
        return artifactUrl
    }
    val manifestDirectory = manifestPath.substringBeforeLast('/', missingDelimiterValue = "")
    return if (manifestDirectory.isEmpty()) {
        artifactUrl
    } else {
        "$manifestDirectory/$artifactUrl"
    }
}

internal actual fun writeHostFileBytes(path: String, bytes: ByteArray): Boolean {
    return runCatching {
        val fp = fopen(path, "wb") ?: return false
        try {
            bytes.usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), fp)
            }
        } finally {
            fclose(fp)
        }
        true
    }.getOrDefault(false)
}

internal actual fun hostMkdirs(path: String): Boolean {
    if (fileManager.fileExistsAtPath(path)) return true
    return runCatching {
        fileManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
    }.getOrDefault(false)
}
