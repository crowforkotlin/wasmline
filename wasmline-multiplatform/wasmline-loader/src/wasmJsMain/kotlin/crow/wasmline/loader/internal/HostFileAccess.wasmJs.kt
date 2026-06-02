package crow.wasmline.loader.internal

internal actual fun hostPathExists(path: String): Boolean = browserHostPathExists(path)

internal actual fun readHostFileBytes(path: String): ByteArray? = browserReadHostFileBytes(path)

internal actual fun resolveHostArtifactPath(manifestPath: String, artifactUrl: String): String {
    return browserResolveHostArtifactPath(manifestPath, artifactUrl)
}

internal actual fun writeHostFileBytes(path: String, bytes: ByteArray): Boolean =
    browserWriteHostFileBytes(path, bytes)

internal actual fun hostMkdirs(path: String): Boolean =
    browserHostMkdirs(path)
