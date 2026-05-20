package crow.wasmline.loader.internal

internal expect fun hostPathExists(path: String): Boolean

internal expect fun readHostFileBytes(path: String): ByteArray?

internal expect fun resolveHostArtifactPath(manifestPath: String, artifactUrl: String): String
