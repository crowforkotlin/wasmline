package crow.wasmline.loader.internal.crypto

import crow.wasmline.loader.internal.secureRandom
import okio.ByteString.Companion.toByteString

/**
 * KeyGenerate
 *
 * 2026/2/10 19:56
 * @author crowforkotlin
 * @formatter:on
 */

/** Returns a new `<publicKey / privateKey>` KeyPair. */
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
