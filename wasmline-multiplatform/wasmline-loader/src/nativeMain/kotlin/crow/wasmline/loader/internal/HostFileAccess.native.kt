package crow.wasmline.loader.internal

import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Native filesystem operations shared by Kotlin/Native host targets.
 *
 * Author: crowforkotlin
 * Date: 2026-08-19
 */
internal actual fun hostPathExists(path: String): Boolean = runCatching {
    FileSystem.SYSTEM.exists(path.toPath())
}.getOrDefault(false)

internal actual fun readHostFileBytes(path: String): ByteArray? = runCatching {
    FileSystem.SYSTEM.read(path.toPath()) { readByteArray() }
}.getOrNull()

internal actual fun resolveHostArtifactPath(manifestPath: String, artifactUrl: String): String {
    val normalizedUrl = artifactUrl.replace('\\', '/')
    if (normalizedUrl.startsWith('/') || WINDOWS_ABSOLUTE_PATH.matches(normalizedUrl)) return artifactUrl
    val manifestDirectory = manifestPath.replace('\\', '/').substringBeforeLast('/', "")
    return if (manifestDirectory.isEmpty()) artifactUrl else "$manifestDirectory/$artifactUrl"
}

internal actual fun writeHostFileBytes(path: String, bytes: ByteArray): Boolean = runCatching {
    val target = path.toPath()
    target.parent?.let(FileSystem.SYSTEM::createDirectories)
    FileSystem.SYSTEM.write(target) { write(bytes) }
    true
}.getOrDefault(false)

internal actual fun hostMkdirs(path: String): Boolean = runCatching {
    FileSystem.SYSTEM.createDirectories(path.toPath())
    true
}.getOrDefault(false)

internal actual fun hostDeleteFile(path: String): Boolean = runCatching {
    val target = path.toPath()
    if (!FileSystem.SYSTEM.exists(target)) return false
    FileSystem.SYSTEM.delete(target)
    true
}.getOrDefault(false)

internal actual fun hostCurrentTimeMs(): Long = systemEpochMsClock()

private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[\\\\/].*")
