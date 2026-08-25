package crow.wasmline.plugin.core.download

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WasmtimeDistributionTest {
    @Test
    fun minimalAndFullReleaseAssetsAreDisjoint() {
        val minimal = "wasmtime-v12.3.4-x86_64-linux-min.tar.xz"
        val full = "wasmtime-v12.3.4-x86_64-linux.tar.xz"

        assertTrue(matchesWasmtimeDistributionAsset(minimal, "x86_64-linux", WasmtimeDistribution.MINIMAL))
        assertFalse(matchesWasmtimeDistributionAsset(minimal, "x86_64-linux", WasmtimeDistribution.FULL))
        assertTrue(matchesWasmtimeDistributionAsset(full, "x86_64-linux", WasmtimeDistribution.FULL))
        assertFalse(matchesWasmtimeDistributionAsset(full, "x86_64-linux", WasmtimeDistribution.MINIMAL))
    }

    @Test
    fun excludesCApiPulleyVariantsAndOtherPlatformsForBothDistributions() {
        listOf(WasmtimeDistribution.MINIMAL, WasmtimeDistribution.FULL).forEach { distribution ->
            assertFalse(
                matchesWasmtimeDistributionAsset(
                    "wasmtime-v12.3.4-x86_64-linux-c-api.tar.xz",
                    "x86_64-linux",
                    distribution,
                ),
            )
            assertFalse(
                matchesWasmtimeDistributionAsset(
                    "wasmtime-v12.3.4-x86_64-linux-pulley-min-c-api.tar.xz",
                    "x86_64-linux",
                    distribution,
                ),
            )
            assertFalse(
                matchesWasmtimeDistributionAsset(
                    "wasmtime-v12.3.4-x86_64-linux-pulley.tar.xz",
                    "x86_64-linux",
                    distribution,
                ),
            )
            assertFalse(
                matchesWasmtimeDistributionAsset(
                    "wasmtime-v12.3.4-aarch64-linux.tar.xz",
                    "x86_64-linux",
                    distribution,
                ),
            )
        }
    }
}
