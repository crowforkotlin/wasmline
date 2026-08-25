package crow.wasmline.plugin.core.download

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class WasmtimeDownloaderTest {
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
