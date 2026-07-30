@file:Suppress("SpellCheckingInspection")

package crow.wasmline.test.wasmtime

import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineLoadState
import crow.wasmline.WasmlineWarmupMode
import crow.wasmline.loader.WasmlineLoader
import crow.wasmline.wasmlineBootstrap
import crow.wasmline.wasmlineShutdown
import crow.wasmline.wasmlineWarmup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests for MathService using real compiled .cwasm/.pwasm artifacts.
 *
 * These tests load actual Wasmline plugins compiled from Kotlin/Wasm code and
 * perform real host-to-WASM bidirectional calls.
 *
 * 2026-07-30
 * @author crowforkotlin
 */
class NativeMathServiceTest {

    companion object {
        private val PWASM_PATH = "${System.getProperty("user.dir")}/../wasmline-test-plugin/build/wasmline/output/wasmline-test-plugin-1.0.0/wasmline-test-plugin-pulley64.pwasm"
        private val CWASM_PATH = "${System.getProperty("user.dir")}/../wasmline-test-plugin/build/wasmline/output/wasmline-test-plugin-1.0.0/wasmline-test-plugin-x86_64-linux.cwasm"
    }

    /**
     * Tests loading MathService from .pwasm (Pulley) artifact and calling add.
     */
    @Test
    fun loadsAndCallsAddFromPulleyArtifact() {
        val artifactPath = findArtifact(PWASM_PATH)
        
        wasmlineBootstrap()
        wasmlineWarmup(WasmlineWarmupMode.PULLEY)
        
        try {
            val state = WasmlineLoader.loadArtifact(artifactPath, WasmlineConfig(supportConcurrent = false))
            assertTrue(state is WasmlineLoadState.Loaded, "Failed to load artifact: $state")
            
            val loader = (state as WasmlineLoadState.Loaded).loader
            loader.load<crow.wasmline.test.plugin.MathService>().use { mathService ->
                assertEquals(42, mathService.add(20, 22))
                assertEquals(0, mathService.add(-5, 5))
                assertEquals(100, mathService.add(100, 0))
            }
        } finally {
            wasmlineShutdown()
        }
    }

    /**
     * Tests loading MathService from .cwasm (Cranelift) artifact and calling subtract/multiply.
     */
    @Test
    fun loadsAndCallsSubtractFromCraneliftArtifact() {
        val artifactPath = findArtifact(CWASM_PATH)
        
        wasmlineBootstrap()
        wasmlineWarmup(WasmlineWarmupMode.CRANELIFT)
        
        try {
            val state = WasmlineLoader.loadArtifact(artifactPath, WasmlineConfig(supportConcurrent = true))
            assertTrue(state is WasmlineLoadState.Loaded)
            
            val loader = (state as WasmlineLoadState.Loaded).loader
            loader.load<crow.wasmline.test.plugin.MathService>().use { mathService ->
                assertEquals(8, mathService.subtract(15, 7))
                assertEquals(-3, mathService.subtract(2, 5))
                assertEquals(0, mathService.subtract(42, 42))
                
                assertEquals(50L, mathService.multiply(5L, 10L))
                assertEquals(-20L, mathService.multiply(-4L, 5L))
                assertEquals(0L, mathService.multiply(100L, 0L))
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
        val artifactPath = findArtifact(PWASM_PATH)
        
        wasmlineBootstrap()
        wasmlineWarmup(WasmlineWarmupMode.PULLEY)
        
        try {
            val state = WasmlineLoader.loadArtifact(artifactPath, WasmlineConfig(supportConcurrent = false))
            val loader = (state as WasmlineLoadState.Loaded).loader
            
            loader.load<crow.wasmline.test.plugin.MathService>().use { mathService ->
                repeat(10) { i ->
                    val a = i * 10
                    val b = i * 5
                    assertEquals(a + b, mathService.add(a, b))
                }
            }
        } finally {
            wasmlineShutdown()
        }
    }

    private fun findArtifact(path: String): String {
        val file = java.io.File(path)
        assertTrue(file.exists(), "Artifact not found: $path. Run './gradlew :wasmline-test-plugin:build' first.")
        return file.absolutePath
    }
}
