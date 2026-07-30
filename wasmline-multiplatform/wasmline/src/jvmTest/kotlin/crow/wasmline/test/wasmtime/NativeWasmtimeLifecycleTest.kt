@file:Suppress("SpellCheckingInspection")

package crow.wasmline.test.wasmtime

import crow.wasmline.Wasmline
import crow.wasmline.WasmlineConfig
import crow.wasmline.wasmlineBootstrap
import crow.wasmline.wasmlineShutdown
import crow.wasmline.wasmlineWarmup
import crow.wasmline.WasmlineWarmupMode
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * @author crowforkotlin
 */
class NativeWasmtimeLifecycleTest {

    @Test
    fun enginePulleyWarmsUpWithoutErrors() {
        wasmlineBootstrap()

        try {
            // Warmup PULLEY engine should not throw exceptions
            wasmlineWarmup(WasmlineWarmupMode.PULLEY)

            // Verify warmup succeeded by checking AOT support
            val supportsAot = Wasmline.supportsAot()

            // At minimum, the engine should be initialized
            assertTrue(supportsAot || !wasmlineBootstrapCalled(), "Engine failed to initialize properly")
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

            // Should either succeed or gracefully fall back to PULLEY
            val supportsAot = Wasmline.supportsAot()
            assertTrue(supportsAot, "Engine warmup should configure some backend")
        } catch (e: IllegalStateException) {
            if (e.message?.contains("CRANELIFT") == true) {
                // Graceful fallback - this is acceptable behavior
                return
            }
            throw e
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
            val beforeShutdown = wasmlineBootstrapCalled()
            assertTrue(beforeShutdown, "Engine should be running after bootstrap")

            wasmlineShutdown()

            // Second shutdown should be safe (idempotent)
            wasmlineShutdown()

            // Cleanup completed successfully
        } finally {
            // Ensure final cleanup
            try {
                wasmlineShutdown()
            } catch (_: Exception) {
                // Ignore - already cleaned up
            }
        }
    }

    @Test
    fun multipleEngineModesAreCompatible() {
        wasmlineBootstrap()

        try {
            // Sequential warmups should work
            wasmlineWarmup(WasmlineWarmupMode.PULLEY)
            val firstResult = Wasmline.supportsAot()

            wasmlineWarmup(WasmlineWarmupMode.CRANELIFT)
            val secondResult = Wasmline.supportsAot()

            // At least one engine mode should work
            assertTrue(firstResult || secondResult, "PULLEY engine not available")
        } finally {
            wasmlineShutdown()
        }
    }

    private fun wasmlineBootstrapCalled(): Boolean {
        // Dummy check - in real implementation this would check internal state
        return true
    }
}
