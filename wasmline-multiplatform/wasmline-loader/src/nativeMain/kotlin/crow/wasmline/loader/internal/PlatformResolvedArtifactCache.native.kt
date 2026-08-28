package crow.wasmline.loader.internal

internal actual fun platformResolvedArtifactExists(key: String): Boolean = false

internal actual fun cachePlatformResolvedArtifact(key: String, bytes: ByteArray): Boolean = false
