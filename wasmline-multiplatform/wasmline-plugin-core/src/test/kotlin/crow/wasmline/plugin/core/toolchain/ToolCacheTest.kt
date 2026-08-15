package crow.wasmline.plugin.core.toolchain

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ToolCacheTest {
    @Test
    fun resolvesOnlyUntamperedMarkedEntry() = withTemporaryDirectory { root ->
        val cache = ToolCache(root)
        val spec = testSpec()
        val directory = cache.directoryFor(spec).apply { mkdirs() }
        val executable = File(directory, spec.entryFileName).apply { writeText("verified") }
        cache.writeMarker(spec, executable.name)

        assertEquals(executable.canonicalFile, cache.resolve(spec)?.file?.canonicalFile)

        executable.writeText("tampered")
        assertNull(cache.resolve(spec))
    }

    @Test
    fun rejectsMismatchedDownloadedDigest() = withTemporaryDirectory { root ->
        val download = File(root, "tool.bin").apply { writeText("wrong") }
        ToolDownloader().use { downloader ->
            assertFailsWith<IllegalArgumentException> {
                downloader.verifyDigest(download, testSpec())
            }
        }
    }

    @Test
    fun rejectsMismatchedDownloadedSize() = withTemporaryDirectory { root ->
        val download = File(root, "tool.bin").apply { writeText("truncated") }
        ToolDownloader().use { downloader ->
            assertFailsWith<IllegalArgumentException> {
                downloader.verifySize(download, testSpec())
            }
        }
    }

    private fun testSpec(): ToolAssetSpec = ToolAssetSpec(
        tool = WasmlineTool.WASM_TOOLS,
        version = "test",
        platform = "test-platform",
        assetId = 1,
        size = 5,
        archiveName = "tool.bin",
        downloadUrl = "https://example.invalid/tool.bin",
        sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        distribution = ToolDistribution.FILE,
        entryFileName = "tool",
        executable = true,
    )
}

private inline fun withTemporaryDirectory(block: (File) -> Unit) {
    val directory = createTempDirectory("wasmline-tools-test").toFile()
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}
