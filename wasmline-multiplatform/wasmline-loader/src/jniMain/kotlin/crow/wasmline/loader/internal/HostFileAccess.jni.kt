package crow.wasmline.loader.internal

import java.io.File

internal actual fun hostPathExists(path: String): Boolean {
    return File(path).exists()
}

internal actual fun readHostFileBytes(path: String): ByteArray? {
    return runCatching { File(path).readBytes() }.getOrNull()
}

internal actual fun resolveHostArtifactPath(manifestPath: String, artifactUrl: String): String {
    val artifactFile = File(artifactUrl)
    if (artifactFile.isAbsolute || WINDOWS_ABSOLUTE_PATH.matches(artifactUrl)) {
        return artifactFile.path
    }
    return File(File(manifestPath).parentFile ?: File("."), artifactUrl).path
}

private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[\\\\/].*")

internal actual fun writeHostFileBytes(path: String, bytes: ByteArray): Boolean {
    return runCatching {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        true
    }.getOrDefault(false)
}

internal actual fun hostMkdirs(path: String): Boolean {
    return runCatching { File(path).mkdirs() || File(path).isDirectory }.getOrDefault(false)
}

internal actual fun hostDeleteFile(path: String): Boolean {
    return runCatching { File(path).delete() }.getOrDefault(false)
}

internal actual fun hostCurrentTimeMs(): Long = System.currentTimeMillis()
