package crow.wasmline.plugin.core.aot

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineEngineKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies canonical physical target normalization before matrix expansion.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
class WasmlineArtifactTargetFactoryTest {
    @Test
    fun normalizesCraneliftAndPulleyTargets() {
        val targets = WasmlineArtifactTargetFactory.create(listOf("x86_64-linux", "pulley32", "pulley64"))
        val cranelift = targets.single { it.artifactBackend == WasmlineEngineKind.CRANELIFT }
        val pulley32 = targets.single { it.architecture == "pulley32" }

        assertEquals(WasmlineArtifactFormat.CWASM, cranelift.format)
        assertEquals("x86_64-unknown-linux-gnu", cranelift.normalizedTarget)
        assertEquals("linux", cranelift.operatingSystem)
        assertEquals(64, cranelift.pointerWidth)
        assertEquals("baseline-v1", cranelift.cpuFeatureProfile)
        assertEquals(WasmlineArtifactFormat.PWASM, pulley32.format)
        assertEquals(32, pulley32.pointerWidth)
        assertNull(pulley32.operatingSystem)
    }

    @Test
    fun rejectsIosCraneliftAndDuplicateNormalizedTargets() {
        assertFailsWith<IllegalArgumentException> {
            WasmlineArtifactTargetFactory.create(listOf("aarch64-ios"))
        }
        val duplicate = assertFailsWith<IllegalArgumentException> {
            WasmlineArtifactTargetFactory.create(listOf("x86_64-linux", "x86_64-unknown-linux-gnu"))
        }
        assertTrue(duplicate.message.orEmpty().contains("Duplicate AOT targets"))
    }
}
