@file:OptIn(crow.wasmline.loader.tooling.WasmlineLoaderToolingApi::class)

package crow.wasmline.plugin.core.manifest

import crow.wasmline.loader.tooling.WasmlineSigningKeyPair
import crow.wasmline.loader.tooling.WasmlineSigningTooling
import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import okio.ByteString.Companion.toByteString

/**
 * Contains hex-encoded Ed25519 manifest key material.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
public data class ManifestKeyPair(val publicKeyHex: String, val privateKeyHex: String)

/**
 * Generates Ed25519 manifest signing keys without exposing loader crypto types.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
public object ManifestKeyGenerator {
    /** Generates one Ed25519 key pair for the current manifest format. */
    public fun generate(): ManifestKeyPair = WasmlineSigningTooling.generateEd25519KeyPair().toManifestKeyPair()

    private fun WasmlineSigningKeyPair.toManifestKeyPair(): ManifestKeyPair = ManifestKeyPair(
        publicKeyHex = publicKey.toByteString().hex(),
        privateKeyHex = privateKey.toByteString().hex(),
    )
}
