@file:Suppress("SpellCheckingInspection")

package crow.wasmline.test.wasmtime

import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineLoadResult
import crow.wasmline.WasmlineWarmupMode
import crow.wasmline.link
import crow.wasmline.loader.WasmlineLoader
import crow.wasmline.wasmlineBootstrap
import crow.wasmline.wasmlineShutdown
import crow.wasmline.wasmlineWarmup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end tests for MathService using a real compiled host artifact.
 *
 * These tests load actual Wasmline plugins compiled from Kotlin/Wasm code and
 * perform real host-to-WASM bidirectional calls.
 *
 * 2026-07-30
 * @author crowforkotlin
 */
class NativeMathServiceTest {

    companion object {
        private val ARTIFACT_PATH = requireNotNull(System.getProperty("wasmline.plugin.artifact.path")) {
            "Missing wasmline plugin artifact path."
        }
    }

    /**
     * Tests loading MathService from the host artifact and calling add.
     */
    @Test
    fun loadsAndCallsAddFromArtifact() {
        val artifactPath = findArtifact(ARTIFACT_PATH)

        wasmlineBootstrap()
        wasmlineWarmup(WasmlineWarmupMode.CRANELIFT)

        try {
            val wasmline = assertIs<WasmlineLoadResult.Success>(
                WasmlineLoader.load(artifactPath, WasmlineConfig(supportConcurrent = false)),
            ).wasmline
            try {
                val mathService = wasmline.link<crow.wasmline.test.plugin.MathService>()
                assertEquals(42, mathService.add(20, 22))
                assertEquals(0, mathService.add(-5, 5))
                assertEquals(100, mathService.add(100, 0))
            } finally {
                wasmline.close()
            }
        } finally {
            wasmlineShutdown()
        }
    }

    /**
     * Tests loading MathService from the host artifact and calling subtract/multiply.
     */
    @Test
    fun loadsAndCallsSubtractFromCraneliftArtifact() {
        val artifactPath = findArtifact(ARTIFACT_PATH)

        wasmlineBootstrap()
        wasmlineWarmup(WasmlineWarmupMode.CRANELIFT)

        try {
            val wasmline = assertIs<WasmlineLoadResult.Success>(
                WasmlineLoader.load(artifactPath, WasmlineConfig(supportConcurrent = true)),
            ).wasmline
            try {
                val mathService = wasmline.link<crow.wasmline.test.plugin.MathService>()
                assertEquals(8, mathService.subtract(15, 7))
                assertEquals(-3, mathService.subtract(2, 5))
                assertEquals(0, mathService.subtract(42, 42))

                assertEquals(50L, mathService.multiply(5L, 10L))
                assertEquals(-20L, mathService.multiply(-4L, 5L))
                assertEquals(0L, mathService.multiply(100L, 0L))
            } finally {
                wasmline.close()
            }
        } finally {
            wasmlineShutdown()
        }
    }

    /**
     * Tests multiple sequential calls to same service instance.
     */
    @Test
    fun performsMultipleCallsToSameService() {
        val artifactPath = findArtifact(ARTIFACT_PATH)

        wasmlineBootstrap()
        wasmlineWarmup(WasmlineWarmupMode.CRANELIFT)

        try {
            val wasmline = assertIs<WasmlineLoadResult.Success>(
                WasmlineLoader.load(artifactPath, WasmlineConfig(supportConcurrent = false)),
            ).wasmline
            try {
                val mathService = wasmline.link<crow.wasmline.test.plugin.MathService>()
                repeat(10) { i ->
                    val a = i * 10
                    val b = i * 5
                    assertEquals(a + b, mathService.add(a, b))
                }
            } finally {
                wasmline.close()
            }
        } finally {
            wasmlineShutdown()
        }
    }

    private fun findArtifact(path: String): String {
        val file = java.io.File(path)
        assertTrue(file.exists(), "Artifact not found: $path. Run './gradlew jvmTest' first.")
        return file.absolutePath
    }
}
