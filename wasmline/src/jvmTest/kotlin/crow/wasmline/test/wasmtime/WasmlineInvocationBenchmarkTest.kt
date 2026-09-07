package crow.wasmline.test.wasmtime

import crow.wasmline.WasmlineArtifactFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WasmlineInvocationBenchmarkTest {
    @Test
    fun acceptsOnlyPrecompiledAotSuffixes() {
        assertEquals(WasmlineArtifactFormat.CWASM, WasmlineInvocationBenchmark.aotFormat("plugin.cwasm"))
        assertEquals(WasmlineArtifactFormat.PWASM, WasmlineInvocationBenchmark.aotFormat("plugin.pwasm"))
        assertFailsWith<IllegalArgumentException> {
            WasmlineInvocationBenchmark.aotFormat("plugin.wasm")
        }
    }

    @Test
    fun parsesLinuxVmHwmInKibibytes() {
        assertEquals(
            123456L,
            WasmlineInvocationBenchmark.parseLinuxPeakRssKiB(
                "Name:\tjava\nVmRSS:\t123 kB\nVmHWM:\t123456 kB\n",
            ),
        )
        assertNull(WasmlineInvocationBenchmark.parseLinuxPeakRssKiB("Name:\tjava\nVmRSS:\t123 kB\n"))
    }
}
