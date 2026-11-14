@file:OptIn(crow.wasmline.InternalWasmlineRuntimeApi::class)

package crow.wasmline.loader.internal

import crow.wasmline.WasmlineWeb

private val loaderVerifiedArtifactIdentities = mutableMapOf<String, Any>()

internal actual fun platformResolvedArtifactExists(key: String): Boolean {
    val verifiedIdentity = loaderVerifiedArtifactIdentities[key] ?: return false
    if (WasmlineWeb.bytesIdentity(key) === verifiedIdentity) return true
    loaderVerifiedArtifactIdentities.remove(key)
    return false
}

internal actual fun cachePlatformResolvedArtifact(key: String, bytes: ByteArray): Boolean {
    WasmlineWeb.registerBytes(key, bytes)
    loaderVerifiedArtifactIdentities[key] = requireNotNull(WasmlineWeb.bytesIdentity(key))
    return true
}
