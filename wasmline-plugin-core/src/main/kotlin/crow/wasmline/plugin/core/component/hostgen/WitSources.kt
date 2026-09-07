package crow.wasmline.plugin.core.component.hostgen

import java.io.File
import java.security.MessageDigest

internal data class WitSourceSet(val root: File, val rootFiles: List<File>, val allFiles: List<File>, val sha256: String) {
    val source: String = rootFiles.joinToString("\n") { it.readText(Charsets.UTF_8).normalizeWitNewlines() }
}

internal object WitSources {
    fun load(path: File): WitSourceSet {
        require(path.exists()) { "WIT path does not exist: ${path.absolutePath}" }
        if (path.isFile) {
            require(path.extension.equals("wit", ignoreCase = true)) { "WIT source must use the .wit extension: ${path.absolutePath}" }
            return WitSourceSet(
                root = path.parentFile,
                rootFiles = listOf(path),
                allFiles = listOf(path),
                sha256 = sha256Hex(path.readText(Charsets.UTF_8).normalizeWitNewlines().encodeToByteArray()),
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

        val digest = MessageDigest.getInstance("SHA-256")
        allFiles.forEach { file ->
            digest.update(file.relativeTo(path).invariantSeparatorsPath.encodeToByteArray())
            digest.update(0)
            digest.update(file.readText(Charsets.UTF_8).normalizeWitNewlines().encodeToByteArray())
            digest.update(0)
        }
        return WitSourceSet(
            root = path,
            rootFiles = rootFiles,
            allFiles = allFiles,
            sha256 = digest.digest().toHex(),
        )
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}

private fun String.normalizeWitNewlines(): String = replace("\r\n", "\n").replace('\r', '\n')
