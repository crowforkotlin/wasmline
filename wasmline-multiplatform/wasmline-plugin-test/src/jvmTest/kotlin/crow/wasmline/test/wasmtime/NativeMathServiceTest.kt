@file:Suppress("SpellCheckingInspection")

package crow.wasmline.test.wasmtime

import crow.wasmline.link
import crow.wasmline.test.plugin.MathService
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end tests for MathService using a real compiled host artifact.
 *
 * These tests load actual Wasmline plugins compiled from Kotlin/Wasm code and
 * perform real host-to-WASM bidirectional calls.
 *
 * Date: 2026-07-30
 * Author: crowforkotlin
 */
class NativeMathServiceTest {

    /**
     * Tests loading MathService from the host artifact and calling add.
     */
    @Test
    fun loadsAndCallsAddFromArtifact() {
        NativePluginTestSupport.withLoadedPlugin { wasmline ->
            val mathService = wasmline.link<MathService>()
            assertEquals(42, mathService.add(20, 22))
            assertEquals(0, mathService.add(-5, 5))
            assertEquals(100, mathService.add(100, 0))
        }
    }

    /**
     * Tests loading MathService from the host artifact and calling subtract/multiply.
     */
    @Test
    fun loadsAndCallsSubtractFromCraneliftArtifact() {
        NativePluginTestSupport.withLoadedPlugin(supportConcurrent = true) { wasmline ->
            val mathService = wasmline.link<MathService>()
            assertEquals(8, mathService.subtract(15, 7))
            assertEquals(-3, mathService.subtract(2, 5))
            assertEquals(0, mathService.subtract(42, 42))

            assertEquals(50L, mathService.multiply(5L, 10L))
            assertEquals(-20L, mathService.multiply(-4L, 5L))
            assertEquals(0L, mathService.multiply(100L, 0L))
        }
    }

    /**
     * Tests multiple sequential calls to same service instance.
     */
    @Test
    fun performsMultipleCallsToSameService() {
        NativePluginTestSupport.withLoadedPlugin { wasmline ->
            val mathService = wasmline.link<MathService>()
            repeat(10) { i ->
                val a = i * 10
                val b = i * 5
                assertEquals(a + b, mathService.add(a, b))
            }
        }
    }

    /**
     * Tests concurrent calls through the thread-safe native loading path.
     */
    @Test
    fun performsConcurrentCallsWhenEnabled() {
        NativePluginTestSupport.withLoadedPlugin(supportConcurrent = true) { wasmline ->
            val mathService = wasmline.link<MathService>()
            val executor = Executors.newFixedThreadPool(4)
            try {
                val tasks = (0 until 16).map { index ->
                    Callable { mathService.add(index, index * 2) }
                }
                executor.invokeAll(tasks).forEachIndexed { index, result ->
                    assertEquals(index * 3, result.get())
                }
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
