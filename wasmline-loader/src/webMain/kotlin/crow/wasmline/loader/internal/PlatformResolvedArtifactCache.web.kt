package crow.wasmline.loader.internal

import crow.wasmline.WasmlineWeb

private val loaderVerifiedArtifactKeys = mutableSetOf<String>()

internal actual fun platformResolvedArtifactExists(key: String): Boolean {
    if (key !in loaderVerifiedArtifactKeys) return false
    if (WasmlineWeb.hasBytes(key)) return true
    loaderVerifiedArtifactKeys -= key
    return false
}

internal actual fun cachePlatformResolvedArtifact(key: String, bytes: ByteArray): Boolean {
    WasmlineWeb.registerBytes(key, bytes)
    loaderVerifiedArtifactKeys += key
    return true
}
