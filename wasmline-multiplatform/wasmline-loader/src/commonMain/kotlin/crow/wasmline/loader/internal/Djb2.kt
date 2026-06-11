package crow.wasmline.loader.internal

/**
 * DJB2 hash — fast, minimal, 32-bit output (8 hex chars).
 *
 * Suitable for cache key generation where cryptographic strength is not required.
 * Reference: http://www.cse.yorku.ca/~oz/hash.html
 */
internal object Djb2 {

    fun hash(data: ByteArray): Int {
        var h = 5381
        for (b in data) { h += (h shl 5) + (b.toInt() and 0xFF) }
        return h
    }

    fun hashToHex8(data: ByteArray): String {
        return hash(data).toUInt().toString(16).padStart(8, '0')
    }
}
