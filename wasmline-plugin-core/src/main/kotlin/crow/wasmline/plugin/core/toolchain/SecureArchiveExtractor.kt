package crow.wasmline.plugin.core.toolchain

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream

/** Extracts known tool archives while rejecting traversal and link entries. */
internal object SecureArchiveExtractor {
    fun extract(archive: File, distribution: ToolDistribution, destination: File) {
        require(archive.isFile) { "Tool archive does not exist: " + archive.absolutePath }
        destination.mkdirs()
        when (distribution) {
            ToolDistribution.TAR_GZ -> extractTarGz(archive, destination)
            ToolDistribution.ZIP -> extractZip(archive, destination)
            ToolDistribution.FILE -> error("A standalone file does not require archive extraction.")
        }
    }

    private fun extractTarGz(archive: File, destination: File) {
        archive.inputStream().use { input ->
            GZIPInputStream(input).use { gzip ->
                TarArchiveInputStream(gzip).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        require(!entry.isSymbolicLink && !entry.isLink) {
                            "Tool archive contains an unsupported link entry: " + entry.name
                        }
                        val output = safeOutputPath(destination, entry.name)
                        if (entry.isDirectory) {
                            Files.createDirectories(output)
                        } else {
                            output.parent?.let { parent -> Files.createDirectories(parent) }
                            Files.copy(tar, output, StandardCopyOption.REPLACE_EXISTING)
                            if (entry.mode and 0b001_001_001 != 0) {
                                output.toFile().setExecutable(true, false)
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
    }

    private fun extractZip(archive: File, destination: File) {
        ZipArchiveInputStream(archive.inputStream()).use { zip ->
            var entry = zip.nextZipEntry
            while (entry != null) {
                require(!entry.isUnixSymlink) {
                    "Tool archive contains an unsupported link entry: " + entry.name
                }
                val output = safeOutputPath(destination, entry.name)
                if (entry.isDirectory) {
                    Files.createDirectories(output)
                } else {
                    output.parent?.let { parent -> Files.createDirectories(parent) }
                    Files.copy(zip, output, StandardCopyOption.REPLACE_EXISTING)
                }
                entry = zip.nextZipEntry
            }
        }
    }

    private fun safeOutputPath(destination: File, entryName: String): Path {
        require(entryName.isNotBlank()) { "Tool archive contains an empty entry name." }
        val root = destination.toPath().toAbsolutePath().normalize()
        val output = root.resolve(entryName.replace('\\', '/')).normalize()
        require(output.startsWith(root) && output != root) {
            "Tool archive entry escapes its destination: " + entryName
        }
        return output
    }
}
