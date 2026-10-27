package crow.wasmline.plugin.core.component.hostgen

import java.io.File
import java.security.MessageDigest

internal data class WitSourceSet(val root: File, val rootFiles: List<File>, val allFiles: List<File>, val sha256: String) {
    val source: String = rootFiles.joinToString("\n") { it.readText(Charsets.UTF_8).normalizeWitNewlines() }
}

/**
 * Reads WIT source trees and derives the identity recorded as `component.wit.sha256`.
 *
 * A single `.wit` file and a directory containing only that file produce the
 * same digest. Paths are relative to the WIT root, so moving the tree does not
 * change the value.
 */
internal object WitSources {
    fun load(path: File): WitSourceSet {
        require(path.exists()) { "WIT path does not exist: ${path.absolutePath}" }
        if (path.isFile) {
            require(path.extension.equals("wit", ignoreCase = true)) { "WIT source must use the .wit extension: ${path.absolutePath}" }
            return WitSourceSet(
                root = path.parentFile,
                rootFiles = listOf(path),
                allFiles = listOf(path),
                sha256 = digestOf(listOf(path.name to path.readText(Charsets.UTF_8))),
            )
        }

        val allFiles = path.walkTopDown()
            .filter { it.isFile && it.extension.equals("wit", ignoreCase = true) }
            .sortedBy { it.relativeTo(path).invariantSeparatorsPath }
            .toList()
        require(allFiles.isNotEmpty()) { "WIT directory contains no .wit files: ${path.absolutePath}" }
        val rootFiles = allFiles.filter { file ->
            val relative = file.relativeTo(path).invariantSeparatorsPath
            relative != "deps" && !relative.startsWith("deps/")
        }
        require(rootFiles.isNotEmpty()) { "WIT directory contains no root package .wit files outside deps/: ${path.absolutePath}" }

        return WitSourceSet(
            root = path,
            rootFiles = rootFiles,
            allFiles = allFiles,
            sha256 = digestOf(
                allFiles.map { file ->
                    file.relativeTo(path).invariantSeparatorsPath to file.readText(Charsets.UTF_8)
                },
            ),
        )
    }

    /** Hashes relative path, NUL, newline-normalized content, NUL for every entry in order. */
    private fun digestOf(entries: List<Pair<String, String>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.forEach { (relativePath, content) ->
            digest.update(relativePath.encodeToByteArray())
            digest.update(0)
            digest.update(content.normalizeWitNewlines().encodeToByteArray())
            digest.update(0)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}

private fun String.normalizeWitNewlines(): String = replace("\r\n", "\n").replace('\r', '\n')
