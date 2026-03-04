@file:Suppress("SpellCheckingInspection")

package crow.wasmline.loader.internal

import crow.wasmline.loader.internal.crypto.EcdsaP256
import crow.wasmline.loader.internal.crypto.SignatureAlgorithm
import java.security.SecureRandom

internal actual val systemEpochMsClock: () -> Long
    get() = System::currentTimeMillis

internal fun secureRandom(): SecureRandom {
    return SecureRandom().also { it.nextLong() } // Force seeding.
}

internal actual val ecdsaP256: SignatureAlgorithm = EcdsaP256(secureRandom())