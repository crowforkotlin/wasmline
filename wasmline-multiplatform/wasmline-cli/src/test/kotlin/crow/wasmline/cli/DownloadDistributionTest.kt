package crow.wasmline.cli

import crow.wasmline.plugin.core.download.WasmtimeDistribution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DownloadDistributionTest {
    @Test
    fun `runtime distribution remains the default`() {
        assertEquals(WasmtimeDistribution.MINIMAL, DEFAULT_WASMTIME_DISTRIBUTION)
    }

    @Test
    fun `parses full compiler distribution`() {
        assertEquals(WasmtimeDistribution.FULL, parseWasmtimeDistribution("FULL"))
    }

    @Test
    fun `rejects unknown distribution`() {
        assertFailsWith<IllegalStateException> {
            parseWasmtimeDistribution("component")
        }
    }
}
