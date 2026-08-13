package crow.wasmline.loader.internal.crypto

import crow.wasmline.loader.internal.secureRandom
import okio.ByteString.Companion.toByteString

/**
 * KeyGenerate
 * Returns a new `<publicKey / privateKey>` KeyPair.
 *
 * Date: 2026-02-10
 * Author: crowforkotlin
 * @formatter:on
 */
internal fun generateEd25519KeyPair(): KeyPair {
    val secretSeed = ByteArray(Field25519.FIELD_LEN)
    secureRandom().nextBytes(secretSeed)
    return newKeyPairFromSeed(secretSeed.toByteString())
}

internal fun generateKeyPair(signatureAlgorithmId: SignatureAlgorithmId): KeyPair = when (signatureAlgorithmId) {
    SignatureAlgorithmId.Ed25519 -> generateEd25519KeyPair()

    SignatureAlgorithmId.EcdsaP256 -> EcdsaP256(
        secureRandom(),
    ).generateKeyPair()
}
