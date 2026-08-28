package crow.wasmline.loader.internal.crypto

import crow.wasmline.loader.internal.secureRandom
import okio.ByteString.Companion.toByteString

/** Generates a new Ed25519 key pair. */
internal fun generateEd25519KeyPair(): KeyPair {
    val secretSeed = ByteArray(Field25519.FIELD_LEN)
    secureRandom().nextBytes(secretSeed)
    return newKeyPairFromSeed(secretSeed.toByteString())
}
