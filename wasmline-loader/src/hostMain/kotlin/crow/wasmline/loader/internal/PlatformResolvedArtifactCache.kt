package crow.wasmline.loader.internal

/** Returns whether a platform runtime retains a Loader-verified artifact under [key]. */
internal expect fun platformResolvedArtifactExists(key: String): Boolean

/** Stores Loader-verified artifact bytes in a platform runtime cache when supported. */
internal expect fun cachePlatformResolvedArtifact(key: String, bytes: ByteArray): Boolean
