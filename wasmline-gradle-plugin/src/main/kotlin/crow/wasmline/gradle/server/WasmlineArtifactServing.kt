package crow.wasmline.gradle.server

import java.io.File
import java.nio.file.Path

/**
 * Resolves one HTTP request path to a served artifact file and formats index entries.
 *
 * Every resolution is confined to the serve directory: `..` segments, absolute
 * paths, and symlinks that leave the directory are rejected, so the server can
 * never read an unrelated file.
 *
 * Date: 2026-11-11
 * Author: crowforkotlin
 */
internal object WasmlineArtifactServing {

    /**
     * Resolves [filename] to a regular file inside [root].
     *
     * [filename] is a `/`-separated path such as `artifacts/sha256/ab/ab12.pwasm`.
     *
     * @return the canonical file, or `null` when the request escapes [root],
     *   names a directory, or does not exist.
     */
    fun resolveServedFile(root: Path, filename: String): File? {
        if (filename.isEmpty() || filename.contains('\u0000')) return null
        val normalizedRoot = root.toAbsolutePath().normalize()
        val resolved = runCatching { normalizedRoot.resolve(filename).normalize() }.getOrNull() ?: return null
        if (resolved == normalizedRoot || !resolved.startsWith(normalizedRoot)) return null
        val canonicalRoot = runCatching { normalizedRoot.toFile().canonicalFile }.getOrNull() ?: return null
        val canonicalFile = runCatching { resolved.toFile().canonicalFile }.getOrNull() ?: return null
        if (!canonicalFile.isFile) return null
        if (!canonicalFile.toPath().startsWith(canonicalRoot.toPath())) return null
        return canonicalFile
    }

    /**
     * Lists every regular file below [root], including the nested
     * `artifacts/sha256/{prefix}/` package objects.
     *
     * @return files ordered by their `/`-separated relative path.
     */
    fun listServedFiles(root: Path): List<File> {
        val rootFile = root.toAbsolutePath().normalize().toFile()
        if (!rootFile.isDirectory) return emptyList()
        return rootFile
            .walkTopDown()
            .onEnter { it == rootFile || !it.name.startsWith(".") }
            .filter { it.isFile }
            .sortedBy { relativePath(rootFile, it) }
            .toList()
    }

    /** Returns [file] relative to [root] with `/` separators. */
    fun relativePath(root: File, file: File): String = file.relativeTo(root).invariantSeparatorsPath

    /** Renders one index entry with escaped text and a percent-encoded link. */
    fun renderIndexEntry(root: File, file: File): String {
        val relative = relativePath(root, file)
        val escapedName = escapeHtml(relative)
        val href = escapeHtml(encodeUrlPath(relative))
        val sizeKb = file.length() / 1024.0
        return "<li><a href=\"/$href\">$escapedName</a> ($sizeKb KB)</li>"
    }

    /** Percent-encodes each segment of a `/`-separated relative path. */
    fun encodeUrlPath(value: String): String = value.split('/').joinToString("/") { segment -> encodeUrlPathSegment(segment) }

    /** Escapes text for HTML element content and double-quoted attributes. */
    fun escapeHtml(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(character)
            }
        }
    }

    /** Percent-encodes one path segment so an arbitrary file name forms a valid URL. */
    fun encodeUrlPathSegment(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val unsigned = byte.toInt() and 0xFF
            val character = unsigned.toChar()
            if (character.isAsciiUrlSegmentCharacter()) {
                append(character)
            } else {
                append('%')
                append(HEX_DIGITS[unsigned shr 4])
                append(HEX_DIGITS[unsigned and 0x0F])
            }
        }
    }

    private fun Char.isAsciiUrlSegmentCharacter(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' ||
        this == '-' || this == '_' || this == '.' || this == '~'

    private const val HEX_DIGITS: String = "0123456789ABCDEF"
}
