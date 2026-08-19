@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package crow.wasmline.loader.internal

import crow.wasmline.loader.internal.crypto.SignatureAlgorithm
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import okio.ByteString
import platform.windows.FILETIME
import platform.windows.GetSystemTimeAsFileTime

/**
 * Provides the Windows Native cryptography fallback and wall-clock implementation.
 *
 * Author: crowforkotlin
 * Date: 2026-08-19
 */
internal actual val ecdsaP256: SignatureAlgorithm = UnsupportedMingwEcdsaP256

internal actual val systemEpochMsClock: () -> Long = ::currentMingwEpochMs

private fun currentMingwEpochMs(): Long = memScoped {
    val fileTime = alloc<FILETIME>()
    GetSystemTimeAsFileTime(fileTime.ptr)
    val high = fileTime.dwHighDateTime.toLong() and 0xFFFF_FFFFL
    val low = fileTime.dwLowDateTime.toLong() and 0xFFFF_FFFFL
    ((high shl 32) or low) / 10_000L - 11_644_473_600_000L
}

private object UnsupportedMingwEcdsaP256 : SignatureAlgorithm {
    override fun sign(message: ByteString, privateKey: ByteString): ByteString =
        error("ECDSA P-256 signing is not available on Windows Native.")

    override fun verify(message: ByteString, signature: ByteString, publicKey: ByteString): Boolean =
        error("ECDSA P-256 verification is not available on Windows Native.")
}
