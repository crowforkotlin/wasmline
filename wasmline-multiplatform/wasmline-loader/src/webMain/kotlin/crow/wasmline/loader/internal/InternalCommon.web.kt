package crow.wasmline.loader.internal

import crow.wasmline.loader.internal.crypto.SignatureAlgorithm
import okio.ByteString
import kotlin.time.Clock

internal val browserEcdsaP256: SignatureAlgorithm = object : SignatureAlgorithm {
    override fun sign(message: ByteString, privateKey: ByteString): ByteString {
        error("ECDSA P-256 is not supported on the wasmJs loader target yet.")
    }

    override fun verify(message: ByteString, signature: ByteString, publicKey: ByteString): Boolean {
        error("ECDSA P-256 is not supported on the wasmJs loader target yet.")
    }
}

internal val browserSystemEpochMsClock: () -> Long = { Clock.System.now().toEpochMilliseconds() }
