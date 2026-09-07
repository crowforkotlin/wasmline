package crow.wasmline.loader.internal

internal actual fun hostPathExists(path: String): Boolean = browserHostPathExists(path)

internal actual fun hostFileSize(path: String): Long? = browserHostFileSize(path)

internal actual fun readHostFileBytes(path: String): ByteArray? = browserReadHostFileBytes(path)

internal actual fun resolveHostArtifactPath(manifestPath: String, artifactRelativePath: String): String =
    browserResolveHostArtifactPath(manifestPath, artifactRelativePath)

internal actual fun writeHostFileBytes(path: String, bytes: ByteArray): Boolean = browserWriteHostFileBytes(path, bytes)

internal actual fun hostMkdirs(path: String): Boolean = browserHostMkdirs(path)

internal actual fun hostDeleteFile(path: String): Boolean = browserHostDeleteFile(path)

internal actual fun hostCurrentTimeMs(): Long = browserHostCurrentTimeMs()
