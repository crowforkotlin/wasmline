package crow.wasmline.plugin.core.toolchain

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SecureArchiveExtractorTest {
    @Test
    fun extractsFilesInsideDestination() = withArchiveDirectory { root ->
        val archive = File(root, "tool.zip")
        writeZip(archive, "package/bin/tool" to "content")
        val destination = File(root, "output")

        SecureArchiveExtractor.extract(archive, ToolDistribution.ZIP, destination)

        assertEquals("content", File(destination, "package/bin/tool").readText())
    }

    @Test
    fun rejectsZipTraversal() = withArchiveDirectory { root ->
        val archive = File(root, "tool.zip")
        writeZip(archive, "../escaped" to "content")
        val destination = File(root, "output")

        assertFailsWith<IllegalArgumentException> {
            SecureArchiveExtractor.extract(archive, ToolDistribution.ZIP, destination)
        }
        assertFalse(File(root, "escaped").exists())
    }

    private fun writeZip(archive: File, vararg entries: Pair<String, String>) {
        ZipOutputStream(archive.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.encodeToByteArray())
                zip.closeEntry()
            }
        }
    }
}

private inline fun withArchiveDirectory(block: (File) -> Unit) {
    val directory = createTempDirectory("wasmline-archive-test").toFile()
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}
