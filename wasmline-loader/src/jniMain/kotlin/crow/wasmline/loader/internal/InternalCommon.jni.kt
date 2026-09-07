@file:Suppress("SpellCheckingInspection")

package crow.wasmline.loader.internal

import java.security.SecureRandom

internal actual val systemEpochMsClock: () -> Long
    get() = System::currentTimeMillis

internal fun secureRandom(): SecureRandom {
    return SecureRandom().also { it.nextLong() } // Force seeding.
}
