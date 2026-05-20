package crow.wasmline.loader.internal

internal fun browserHostPathExists(path: String): Boolean = false

internal fun browserReadHostFileBytes(path: String): Nothing? = null

internal fun browserResolveHostArtifactPath(manifestPath: String, artifactUrl: String): String {
    return artifactUrl
}
