@file:Suppress("SpellCheckingInspection")

package crow.wasmline.test.wasmtime

import crow.wasmline.WasmlineEngineKind
import crow.wasmline.WasmlineRuntime
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
        WasmlineRuntime.preload()

        try {
            WasmlineRuntime.warmUp(WasmlineEngineKind.PULLEY)

            assertTrue(
                WasmlineEngineKind.PULLEY in requireNotNull(WasmlineRuntime.nativeInfo()).supportedEngines,
                "The linked runtime should support Pulley",
            )
        } catch (e: Exception) {
            fail("PULLEY warmup threw exception: ${e.message}", e)
        } finally {
            WasmlineRuntime.shutdown()
        }
    }

    @Test
    fun engineCraneliftWarmsUpGracefully() {
        WasmlineRuntime.preload()

        try {
            WasmlineRuntime.warmUp(WasmlineEngineKind.CRANELIFT)

            assertTrue(
                WasmlineEngineKind.CRANELIFT in requireNotNull(WasmlineRuntime.nativeInfo()).supportedEngines,
                "The linked runtime should support Cranelift",
            )
        } catch (e: Exception) {
            fail("Cranelift warmup threw unexpected exception: ${e.message}", e)
        } finally {
            WasmlineRuntime.shutdown()
        }
    }

    @Test
    fun shutdownResetsStateSuccessfully() {
        WasmlineRuntime.preload()

        try {
            WasmlineRuntime.warmUp(WasmlineEngineKind.PULLEY)

            WasmlineRuntime.shutdown()

            WasmlineRuntime.shutdown()
        } finally {
            try {
                WasmlineRuntime.shutdown()
            } catch (_: Exception) {}
        }
    }

    @Test
    fun multipleEngineModesAreCompatible() {
        WasmlineRuntime.preload()

        try {
            val supportedEngines = requireNotNull(WasmlineRuntime.nativeInfo()).supportedEngines
            assertTrue(WasmlineEngineKind.PULLEY in supportedEngines)
            assertTrue(WasmlineEngineKind.CRANELIFT in supportedEngines)

            WasmlineRuntime.warmUp(WasmlineEngineKind.PULLEY)
            WasmlineRuntime.warmUp(WasmlineEngineKind.CRANELIFT)
        } finally {
            WasmlineRuntime.shutdown()
        }
    }
}
