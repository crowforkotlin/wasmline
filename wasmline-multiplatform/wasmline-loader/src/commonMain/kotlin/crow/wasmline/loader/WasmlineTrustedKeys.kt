package crow.wasmline.loader

/**
 * Trusted public key lookup for manifest signature verification.
 *
 * Package sources require this lookup to verify their manifest signature before
 * accepting package metadata. Direct local artifact sources are caller-trusted.
 */
fun interface WasmlineTrustedKeys {
    /**
     * Look up a trusted public key by algorithm and optional key ID.
     *
     * @param algorithm Algorithm identifier (for example, `Ed25519`).
     * @param keyId Optional key identifier. `null` matches any key for the algorithm.
     * @return The raw public key bytes, or `null` when no key matches.
     */
    fun getPublicKey(algorithm: String, keyId: String?): ByteArray?
}

/** Immutable trusted-key set with exact-key and algorithm wildcard lookup. */
class WasmlineTrustedKeySet private constructor(private val entries: List<TrustedKeyEntry>) : WasmlineTrustedKeys {

    private data class TrustedKeyEntry(val algorithm: String, val keyId: String?, val publicKey: ByteArray) {
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
        if (keyId != null) {
            entries.firstOrNull { it.algorithm == algorithm && it.keyId == keyId }?.let { return it.publicKey.copyOf() }
        }
        return entries.firstOrNull { it.algorithm == algorithm && it.keyId == null }?.publicKey?.copyOf()
    }

    class Builder {
        private val entries = mutableListOf<TrustedKeyEntry>()

        fun add(algorithm: String, keyId: String?, publicKey: ByteArray): Builder {
            entries.add(TrustedKeyEntry(algorithm, keyId, publicKey.copyOf()))
            return this
        }

        fun addHex(algorithm: String, keyId: String?, publicKeyHex: String): Builder =
            add(algorithm, keyId, publicKeyHex.decodeHexToByteArray())

        fun build(): WasmlineTrustedKeySet = WasmlineTrustedKeySet(entries.toList())
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

private fun hexCharToDigit(c: Char): Int? = when (c) {
    in '0'..'9' -> c.code - '0'.code
    in 'a'..'f' -> c.code - 'a'.code + 10
    in 'A'..'F' -> c.code - 'A'.code + 10
    else -> null
}
