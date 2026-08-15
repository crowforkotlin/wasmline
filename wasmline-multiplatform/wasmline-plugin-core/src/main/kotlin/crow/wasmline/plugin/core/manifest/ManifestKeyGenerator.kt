@file:OptIn(crow.wasmline.loader.tooling.WasmlineLoaderToolingApi::class)

package crow.wasmline.plugin.core.manifest

import crow.wasmline.loader.tooling.WasmlineSigningKeyPair
import crow.wasmline.loader.tooling.WasmlineSigningTooling
import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import okio.ByteString.Companion.toByteString

/** Signing algorithms supported by Wasmline's manifest key generator. */
@InternalWasmlineToolingApi
public enum class ManifestSigningAlgorithm {
    Ed25519,
    EcdsaP256,
}

/** Hex-encoded key material emitted by Wasmline's build tools. */
@InternalWasmlineToolingApi
public data class ManifestKeyPair(val publicKeyHex: String, val privateKeyHex: String)

/** Generates signing keys without exposing loader crypto implementation types. */
@InternalWasmlineToolingApi
public object ManifestKeyGenerator {
    public fun generate(algorithm: ManifestSigningAlgorithm): ManifestKeyPair {
        val keyPair = when (algorithm) {
            ManifestSigningAlgorithm.Ed25519 -> WasmlineSigningTooling.generateEd25519KeyPair()
            ManifestSigningAlgorithm.EcdsaP256 -> WasmlineSigningTooling.generateEcdsaP256KeyPair()
        }
        return keyPair.toManifestKeyPair()
    }

    private fun WasmlineSigningKeyPair.toManifestKeyPair(): ManifestKeyPair = ManifestKeyPair(
        publicKeyHex = publicKey.toByteString().hex(),
        privateKeyHex = privateKey.toByteString().hex(),
    )
}
