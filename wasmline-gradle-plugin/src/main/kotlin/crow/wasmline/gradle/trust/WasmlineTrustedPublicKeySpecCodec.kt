package crow.wasmline.gradle.trust

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Stable task-input encoding for one Host trusted public-key declaration. */
internal object WasmlineTrustedPublicKeySpecCodec {
    private const val INLINE = "inline"
    private const val FILE = "file"
    private const val SEPARATOR = "|"
    private const val NULL_KEY_ID = "-"

    fun inline(algorithm: String, keyId: String?, publicKeyHex: String): String = encode(INLINE, algorithm, keyId, publicKeyHex)

    fun file(algorithm: String, keyId: String?, file: File): String =
        encode(FILE, algorithm, keyId, file.absoluteFile.invariantSeparatorsPath)

    fun decode(value: String): TrustedPublicKeySpec {
        val parts = value.split(SEPARATOR)
        require(parts.size == 4) { "Invalid trusted public-key specification." }
        val source = decodeText(parts[3])
        return when (parts[0]) {
            INLINE -> TrustedPublicKeySpec(
                algorithm = decodeText(parts[1]),
                keyId = decodeKeyId(parts[2]),
                inlinePublicKeyHex = source,
                publicKeyFile = null,
            )

            FILE -> TrustedPublicKeySpec(
                algorithm = decodeText(parts[1]),
                keyId = decodeKeyId(parts[2]),
                inlinePublicKeyHex = null,
                publicKeyFile = File(source),
            )

            else -> error("Unsupported trusted public-key specification type '${parts[0]}'.")
        }
    }

    private fun encode(kind: String, algorithm: String, keyId: String?, source: String): String = listOf(
        kind,
        encodeText(algorithm),
        keyId?.let(::encodeText) ?: NULL_KEY_ID,
        encodeText(source),
    ).joinToString(SEPARATOR)

    private fun encodeText(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeText(value: String): String = String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)

    private fun decodeKeyId(value: String): String? = if (value == NULL_KEY_ID) null else decodeText(value)
}

internal data class TrustedPublicKeySpec(
    val algorithm: String,
    val keyId: String?,
    val inlinePublicKeyHex: String?,
    val publicKeyFile: File?,
)
