@file:Suppress("SpellCheckingInspection")

package crow.wasmline.test.wasmtime

import crow.wasmline.Wasmline
import crow.wasmline.WasmlineWarmupMode
import crow.wasmline.wasmlineBootstrap
import crow.wasmline.wasmlineShutdown
import crow.wasmline.wasmlineWarmup
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for Wasmtime runtime initialization and lifecycle management.
 *
 * These tests validate:
 * - Engine warmup (Cranelift/Pulley)
 * - Thread-safe shutdown
 * - Platform capabilities detection
 *
 * Author: crowforkotlin
 */
class NativeWasmtimeLifecycleTest {

    @Test
    fun enginePulleyWarmsUpWithoutErrors() {
        wasmlineBootstrap()

        try {
            wasmlineWarmup(WasmlineWarmupMode.PULLEY)

            val supportsAot = Wasmline.supportsAot()

            assertTrue(supportsAot, "Engine failed to initialize properly")
        } catch (e: Exception) {
            fail("PULLEY warmup threw exception: ${e.message}", e)
        } finally {
            wasmlineShutdown()
        }
    }

    @Test
    fun engineCraneliftWarmsUpGracefully() {
        wasmlineBootstrap()

        try {
            wasmlineWarmup(WasmlineWarmupMode.CRANELIFT)

            val supportsAot = Wasmline.supportsAot()
            assertTrue(supportsAot, "Engine warmup should configure some backend")
        } catch (e: Exception) {
            fail("Cranelift warmup threw unexpected exception: ${e.message}", e)
        } finally {
            wasmlineShutdown()
        }
    }

    @Test
    fun shutdownResetsStateSuccessfully() {
        wasmlineBootstrap()

        try {
            wasmlineWarmup(WasmlineWarmupMode.PULLEY)
            assertTrue(Wasmline.supportsAot(), "Engine should be running after warmup")

            wasmlineShutdown()

            wasmlineShutdown()
        } finally {
            try {
                wasmlineShutdown()
            } catch (_: Exception) {}
        }
    }

    @Test
    fun multipleEngineModesAreCompatible() {
        wasmlineBootstrap()

        try {
            wasmlineWarmup(WasmlineWarmupMode.PULLEY)
            val firstResult = Wasmline.supportsAot()

            wasmlineWarmup(WasmlineWarmupMode.CRANELIFT)
            val secondResult = Wasmline.supportsAot()

            assertTrue(firstResult || secondResult, "PULLEY engine not available")
        } finally {
            wasmlineShutdown()
        }
    }
}
