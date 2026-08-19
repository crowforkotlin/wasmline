@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package crow.wasmline.loader.internal

import crow.wasmline.loader.internal.crypto.SignatureAlgorithm
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import okio.ByteString
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.timespec

/**
 * Provides the Linux Native cryptography fallback and wall-clock implementation.
 *
 * Author: crowforkotlin
 * Date: 2026-08-19
 */
internal actual val ecdsaP256: SignatureAlgorithm = UnsupportedLinuxEcdsaP256

internal actual val systemEpochMsClock: () -> Long = ::currentLinuxEpochMs

private fun currentLinuxEpochMs(): Long = memScoped {
    val value = alloc<timespec>()
    check(clock_gettime(CLOCK_REALTIME, value.ptr) == 0) {
        "Unable to read the Linux Native wall clock."
    }
    value.tv_sec * 1000L + value.tv_nsec / 1_000_000L
}

private object UnsupportedLinuxEcdsaP256 : SignatureAlgorithm {
    override fun sign(message: ByteString, privateKey: ByteString): ByteString =
        error("ECDSA P-256 signing is not available on Linux Native.")

    override fun verify(message: ByteString, signature: ByteString, publicKey: ByteString): Boolean =
        error("ECDSA P-256 verification is not available on Linux Native.")
}
