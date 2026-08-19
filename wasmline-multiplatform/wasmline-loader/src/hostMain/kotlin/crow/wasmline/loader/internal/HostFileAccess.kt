package crow.wasmline.loader.internal

/**
 * Returns whether [path] exists on the host filesystem.
 *
 * Author: crowforkotlin
 * Date: 2026-08-19
 */
internal expect fun hostPathExists(path: String): Boolean

/** Reads [path] into memory, or returns `null` when the host read fails. */
internal expect fun readHostFileBytes(path: String): ByteArray?

/** Resolves a package-relative [artifactUrl] against [manifestPath]. */
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

/**
 * Delete the file at [path]. Returns `true` if the file was deleted, `false` otherwise.
 */
internal expect fun hostDeleteFile(path: String): Boolean

/** Returns the current wall-clock time in milliseconds since epoch. */
internal expect fun hostCurrentTimeMs(): Long
