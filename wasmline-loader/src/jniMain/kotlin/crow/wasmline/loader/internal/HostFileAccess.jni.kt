package crow.wasmline.loader.internal

import java.io.File

internal actual fun hostPathExists(path: String): Boolean = File(path).exists()

internal actual fun hostFileSize(path: String): Long? = runCatching {
    File(path).takeIf(File::isFile)?.length()
}.getOrNull()

internal actual fun readHostFileBytes(path: String): ByteArray? = runCatching { File(path).readBytes() }.getOrNull()

internal actual fun resolveHostArtifactPath(manifestPath: String, artifactRelativePath: String): String {
    val artifactFile = File(artifactRelativePath)
    if (artifactFile.isAbsolute || WINDOWS_ABSOLUTE_PATH.matches(artifactRelativePath)) {
        return artifactFile.path
    }
    return File(File(manifestPath).parentFile ?: File("."), artifactRelativePath).path
}

private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[\\\\/].*")

internal actual fun writeHostFileBytes(path: String, bytes: ByteArray): Boolean = runCatching {
    val file = File(path)
    file.parentFile?.mkdirs()
    file.writeBytes(bytes)
    true
}.getOrDefault(false)

internal actual fun hostMkdirs(path: String): Boolean = runCatching { File(path).mkdirs() || File(path).isDirectory }.getOrDefault(false)

internal actual fun hostDeleteFile(path: String): Boolean = runCatching { File(path).delete() }.getOrDefault(false)

internal actual fun hostCurrentTimeMs(): Long = System.currentTimeMillis()
