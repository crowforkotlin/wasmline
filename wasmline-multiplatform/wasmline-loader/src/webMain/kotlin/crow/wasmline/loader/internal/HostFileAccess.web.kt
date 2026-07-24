package crow.wasmline.loader.internal

internal fun browserHostPathExists(path: String): Boolean = false

internal fun browserReadHostFileBytes(path: String): Nothing? = null

internal fun browserResolveHostArtifactPath(manifestPath: String, artifactUrl: String): String = artifactUrl

internal fun browserWriteHostFileBytes(path: String, bytes: ByteArray): Boolean = false

internal fun browserHostMkdirs(path: String): Boolean = false

internal fun browserHostDeleteFile(path: String): Boolean = false

internal fun browserHostCurrentTimeMs(): Long = 0L
