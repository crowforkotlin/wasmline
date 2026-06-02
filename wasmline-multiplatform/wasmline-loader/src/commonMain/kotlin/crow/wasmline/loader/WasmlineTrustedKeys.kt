package crow.wasmline.loader

/**
 * Trusted public key lookup for manifest signature verification.
 *
 * When a [WasmlineLoadRequest] provides a [trustedKeys] instance, the loader
 * verifies the manifest signature before accepting the package. If no trusted
 * keys are provided (`null`), signature verification is skipped (permissive mode).
 *
 * Use [WasmlineTrustedKeySet] for the common case of a static set of trusted keys.
 *
 * 2026-06-02
 * @author crowforkotlin
 */
fun interface WasmlineTrustedKeys {
    /**
     * Look up a trusted public key by algorithm and optional key ID.
     *
     * @param algorithm Algorithm identifier (e.g. `"Ed25519"`, `"EcdsaP256"`).
     * @param keyId Optional key identifier from [SignedManifestEnvelope.publicKeyId].
     *              `null` matches any key for the given algorithm.
     * @return The raw public key bytes, or `null` if no trusted key matches.
     */
    fun getPublicKey(algorithm: String, keyId: String?): ByteArray?
}

/**
 * Immutable set of trusted public keys built via [Builder].
 *
 * Lookup order:
 * 1. Exact match on `(algorithm, keyId)`
 * 2. Wildcard match on `(algorithm, null)` — any keyId accepted for this algorithm
 *
 * Example:
 * ```kotlin
 * val trustedKeys = WasmlineTrustedKeySet.Builder()
 *     .add(SignatureAlgorithmId.Ed25519.name, keyId = "release-key", publicKey = ed25519KeyBytes)
 *     .add(SignatureAlgorithmId.Ed25519.name, keyId = null, publicKey = fallbackKeyBytes)
 *     .build()
 * ```
 */
class WasmlineTrustedKeySet private constructor(
    private val entries: List<TrustedKeyEntry>,
) : WasmlineTrustedKeys {

    private data class TrustedKeyEntry(
        val algorithm: String,
        val keyId: String?,
        val publicKey: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TrustedKeyEntry) return false
            return algorithm == other.algorithm && keyId == other.keyId && publicKey.contentEquals(other.publicKey)
        }

        override fun hashCode(): Int {
            var result = algorithm.hashCode()
            result = 31 * result + (keyId?.hashCode() ?: 0)
            result = 31 * result + publicKey.contentHashCode()
            return result
        }
    }

    override fun getPublicKey(algorithm: String, keyId: String?): ByteArray? {
        // Exact match first
        if (keyId != null) {
            val exact = entries.firstOrNull { it.algorithm == algorithm && it.keyId == keyId }
            if (exact != null) return exact.publicKey
        }
        // Wildcard: entry with null keyId matches any keyId for this algorithm
        val wildcard = entries.firstOrNull { it.algorithm == algorithm && it.keyId == null }
        if (wildcard != null) return wildcard.publicKey
        return null
    }

    /**
     * Builder for constructing an immutable [WasmlineTrustedKeySet].
     */
    class Builder {
        private val entries = mutableListOf<TrustedKeyEntry>()

        /**
         * Add a trusted public key entry.
         *
         * @param algorithm Algorithm identifier string (use [SignatureAlgorithmId.name]).
         * @param keyId Optional key identifier; `null` acts as a wildcard for the algorithm.
         * @param publicKey Raw public key bytes.
         */
        fun add(algorithm: String, keyId: String?, publicKey: ByteArray): Builder {
            entries.add(TrustedKeyEntry(algorithm, keyId, publicKey))
            return this
        }

        /**
         * Convenience overload accepting a hex-encoded public key string.
         */
        fun addHex(algorithm: String, keyId: String?, publicKeyHex: String): Builder {
            return add(algorithm, keyId, publicKeyHex.decodeHexToByteArray())
        }

        fun build(): WasmlineTrustedKeySet {
            return WasmlineTrustedKeySet(entries.toList())
        }
    }
}

private fun String.decodeHexToByteArray(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length, got $length" }
    return ByteArray(length / 2) { i ->
        val hi = hexCharToDigit(this[i * 2])
        val lo = hexCharToDigit(this[i * 2 + 1])
        require(hi != null && lo != null) { "Invalid hex character at position ${i * 2}" }
        ((hi shl 4) or lo).toByte()
    }
}

private fun hexCharToDigit(c: Char): Int? {
    return when (c) {
        in '0'..'9' -> c.code - '0'.code
        in 'a'..'f' -> c.code - 'a'.code + 10
        in 'A'..'F' -> c.code - 'A'.code + 10
        else -> null
    }
}
