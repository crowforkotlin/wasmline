package crow.wasmline.loader.internal

internal expect fun hostPathExists(path: String): Boolean

internal expect fun readHostFileBytes(path: String): ByteArray?

internal expect fun resolveHostArtifactPath(manifestPath: String, artifactUrl: String): String

/**
 * Write [bytes] to the file at [path], creating parent directories as needed.
 * Returns `true` on success, `false` on failure.
 */
internal expect fun writeHostFileBytes(path: String, bytes: ByteArray): Boolean

/**
 * Create the directory at [path] including any necessary parent directories.
 * Returns `true` if the directory exists after the call, `false` otherwise.
 */
internal expect fun hostMkdirs(path: String): Boolean
