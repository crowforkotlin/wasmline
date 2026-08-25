package crow.wasmline.plugin.core.download

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WasmtimeDownloaderTest {
    @Test
    fun downstreamReleaseTagPrefersFourSegmentTag() {
        assertEquals(
            listOf("v12.3.4.5", "release-v12.3.4.5"),
            wasmtimeReleaseTagCandidates("12.3.4.5"),
        )
        assertEquals(
            listOf("release-v47.0.2", "v47.0.2"),
            wasmtimeReleaseTagCandidates("47.0.2"),
        )
    }

    @Test
    fun releaseChecksumIsNeverSelectedAsCliAsset() {
        assertFalse(matchesWasmtimeDistributionAsset("SHA256SUMS", "all", WasmtimeDistribution.FULL))
        assertTrue(
            matchesWasmtimeDistributionAsset(
                "wasmtime-v12.3.4.5-x86_64-linux-min.tar.gz",
                "x86_64-linux",
                WasmtimeDistribution.MINIMAL,
            ),
        )
    }

    @Test
    fun parsesReleaseChecksums() {
        val digest = "a".repeat(64)
        assertEquals(
            mapOf("wasmtime-v12.3.4.5-x86_64-linux-min.tar.gz" to digest),
            parseWasmtimeSha256Sums(
                "$digest  wasmtime-v12.3.4.5-x86_64-linux-min.tar.gz\n",
            ),
        )
    }

    @Test
    fun minimalDistributionAcceptsCliExecutableWithoutMinSuffix() {
        val root = createTempDirectory("wasmtime-downloader").toFile()
        try {
            val extracted = root.resolve("wasmtime-v12.3.4-x86_64-linux-min").apply { mkdirs() }
            val executable = extracted.resolve(executableName(minimal = false)).apply {
                writeText("test")
                setExecutable(true)
            }

            val resolved = findExtractedWasmtimeExecutable(extracted, WasmtimeDistribution.MINIMAL)

            assertNotNull(resolved)
            assertEquals(executable.canonicalFile, resolved.canonicalFile)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun executableName(minimal: Boolean): String {
        val suffix = if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""
        return "wasmtime" + (if (minimal) "-min" else "") + suffix
    }
}
