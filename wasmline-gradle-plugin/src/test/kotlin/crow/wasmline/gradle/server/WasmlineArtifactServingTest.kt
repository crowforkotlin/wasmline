package crow.wasmline.gradle.server

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies that artifact serving stays inside the serve directory and escapes names.
 *
 * Date: 2026-11-11
 * Author: crowforkotlin
 */
class WasmlineArtifactServingTest {

    @Test
    fun `resolves a file directly inside the serve directory`() = withServedDirectory { root, directory ->
        val artifact = File(directory, "artifact.wasm").apply { writeText("bytes") }

        val resolved = assertNotNull(WasmlineArtifactServing.resolveServedFile(root, "artifact.wasm"))

        assertEquals(artifact.canonicalFile, resolved)
    }

    @Test
    fun `rejects parent traversal outside the serve directory`() = withServedDirectory { root, directory ->
        val outside = File(directory.parentFile, "outside-secret.txt").apply { writeText("secret") }

        assertNull(WasmlineArtifactServing.resolveServedFile(root, "../outside-secret.txt"))
        assertNull(WasmlineArtifactServing.resolveServedFile(root, ".."))
        assertNull(WasmlineArtifactServing.resolveServedFile(root, "nested/../../outside-secret.txt"))
        assertTrue(outside.exists())
    }

    @Test
    fun `rejects absolute paths`() = withServedDirectory { root, _ ->
        val outside = Files.createTempFile("wasmline-outside", ".txt").toFile()

        assertNull(WasmlineArtifactServing.resolveServedFile(root, outside.absolutePath))
        assertNull(WasmlineArtifactServing.resolveServedFile(root, "/etc/passwd"))
    }

    @Test
    fun `rejects a directory entry and a missing file`() = withServedDirectory { root, directory ->
        File(directory, "nested").mkdirs()

        assertNull(WasmlineArtifactServing.resolveServedFile(root, "nested"))
        assertNull(WasmlineArtifactServing.resolveServedFile(root, "missing.wasm"))
    }

    @Test
    fun `rejects a symlink that leaves the serve directory`() = withServedDirectory { root, directory ->
        val outside = Files.createTempFile("wasmline-symlink-target", ".txt").toFile().apply { writeText("secret") }
        val link = File(directory, "link.wasm")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        assertNull(WasmlineArtifactServing.resolveServedFile(root, "link.wasm"))
    }

    @Test
    fun `resolves a nested content-addressed artifact`() = withServedDirectory { root, directory ->
        val artifact = File(directory, "artifacts/sha256/ab/abcdef.wasm").apply {
            parentFile.mkdirs()
            writeText("bytes")
        }

        val resolved = assertNotNull(WasmlineArtifactServing.resolveServedFile(root, "artifacts/sha256/ab/abcdef.wasm"))

        assertEquals(artifact.canonicalFile, resolved)
    }

    @Test
    fun `rejects nested traversal outside the serve directory`() = withServedDirectory { root, _ ->
        assertNull(WasmlineArtifactServing.resolveServedFile(root, "artifacts/../../outside-secret.txt"))
    }

    @Test
    fun `lists nested artifacts with slash-separated relative paths`() = withServedDirectory { root, directory ->
        File(directory, "manifest.wlm").writeText("x")
        File(directory, "artifacts/sha256/ab/abcdef.pwasm").apply {
            parentFile.mkdirs()
            writeText("y")
        }

        val relative = WasmlineArtifactServing.listServedFiles(root).map { WasmlineArtifactServing.relativePath(root.toFile(), it) }

        assertEquals(listOf("artifacts/sha256/ab/abcdef.pwasm", "manifest.wlm"), relative)
        assertTrue(WasmlineArtifactServing.encodeUrlPath(relative.first()).contains("/sha256/"))
    }

    @Test
    fun `escapes markup in the index entry`() = withServedDirectory { root, directory ->
        val artifact = File(directory, "<img src=x onerror=alert(1)>.wasm").apply { writeText("x") }

        val entry = WasmlineArtifactServing.renderIndexEntry(root.toFile(), artifact)

        assertFalse(entry.contains("<img"))
        assertTrue(entry.contains("&lt;img src=x onerror=alert(1)&gt;.wasm"))
        assertTrue(entry.contains("/%3Cimg%20src%3Dx%20onerror%3Dalert%281%29%3E.wasm"))
    }

    @Test
    fun `keeps an ordinary artifact name readable`() = withServedDirectory { root, directory ->
        val artifact = File(directory, "plugin-linux-x86_64.cwasm").apply { writeText("x") }

        val entry = WasmlineArtifactServing.renderIndexEntry(root.toFile(), artifact)

        assertTrue(entry.contains("href=\"/plugin-linux-x86_64.cwasm\""))
        assertTrue(entry.contains(">plugin-linux-x86_64.cwasm<"))
    }

    private fun withServedDirectory(block: (java.nio.file.Path, File) -> Unit) {
        val parent = createTempDirectory("wasmline-serve").toFile()
        val directory = File(parent, "serve").apply { mkdirs() }
        try {
            block(directory.toPath().toAbsolutePath().normalize(), directory)
        } finally {
            parent.deleteRecursively()
        }
    }
}
