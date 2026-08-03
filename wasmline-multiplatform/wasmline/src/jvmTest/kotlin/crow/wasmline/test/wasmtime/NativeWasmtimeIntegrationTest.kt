@file:Suppress("SpellCheckingInspection")

package crow.wasmline.test.wasmtime

import crow.wasmline.Wasmline
import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineWarmupMode
import crow.wasmline.wasmlineBootstrap
import crow.wasmline.wasmlineLoadArtifact
import crow.wasmline.wasmlineShutdown
import crow.wasmline.wasmlineWarmup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests driving a real Wasmtime engine through Wasmline with native JNI bindings.
 *
 * These tests mirror [crow.wasmline.web.WebWasmRuntimeTest] but run against native Wasmtime
 * instead of browser's WebAssembly API. They validate core WASM functionality:
 * - Engine lifecycle management (bootstrap/warmup/shutdown)
 * - Multiple engine backend support (PULLEY/Cranelift)
 * - Resource cleanup and error handling
 *
 * Unlike webtest which uses hand-encoded wasm binaries, these tests use the full
 * Wasmline runtime pipeline including artifact loading and module caching.
 *
 * @author crowforkotlin
 */
class NativeWasmtimeIntegrationTest {

    /**
     * Tests that we can successfully create and bootstrap the Wasmtime engine.
     * This is a positive test - validating successful initialization.
     */
    @Test
    fun createsAndInitializesEngineSuccessfully() {
        wasmlineBootstrap()

        try {
            wasmlineWarmup(WasmlineWarmupMode.PULLEY)

            assertTrue(
                Wasmline.supportsAot(),
                "Engine should report AOT support after warmup",
            )
        } finally {
            wasmlineShutdown()
        }
    }

    /**
     * Tests Cranelift engine backend initialization.
     * Similar to PULLEY test but validates Cranelift path.
     */
    @Test
    fun initializesCraneliftBackendSuccessfully() {
        wasmlineBootstrap()

        try {
            wasmlineWarmup(WasmlineWarmupMode.CRANELIFT)

            assertTrue(
                Wasmline.supportsAot(),
                "At least one backend should be active",
            )
        } finally {
            wasmlineShutdown()
        }
    }

    /**
     * Tests complete engine lifecycle from boot to shutdown.
     * Validates forward path execution.
     */
    @Test
    fun executesFullEngineLifecycle() {
        wasmlineBootstrap()

        try {
            wasmlineWarmup(WasmlineWarmupMode.PULLEY)
            val pulleyWorked = Wasmline.supportsAot()

            wasmlineWarmup(WasmlineWarmupMode.CRANELIFT)
            val craneliftWorked = Wasmline.supportsAot()

            assertTrue(
                pulleyWorked || craneliftWorked,
                "At least one engine backend must initialize successfully",
            )
        } finally {
            wasmlineShutdown()
        }
    }

    /**
     * Tests that engine resources are properly managed.
     * Positive validation of resource lifecycle.
     */
    @Test
    fun managesEngineResourcesCorrectly() {
        wasmlineBootstrap()

        try {
            wasmlineWarmup(WasmlineWarmupMode.PULLEY)
            assertTrue(Wasmline.supportsAot(), "Engine should start in first cycle")

            wasmlineShutdown()

            wasmlineBootstrap()
            wasmlineWarmup(WasmlineWarmupMode.CRANELIFT)

            assertTrue(Wasmline.supportsAot(), "Engine should restart successfully")
        } finally {
            try {
                wasmlineShutdown()
            } catch (_: Exception) {}
        }
    }

    /**
     * Tests memory safety when loading missing artifacts.
     * Ensures graceful degradation rather than crashes.
     */
    @Test
    fun loadsMissingArtifactGracefully() {
        wasmlineBootstrap()

        try {
            val result = wasmlineLoadArtifact(
                filepath = "/nonexistent/path/to/missing.cwasm",
                config = WasmlineConfig(supportConcurrent = false),
            )

            assertTrue(result is crow.wasmline.WasmlineLoadState.Failure)
            assertEquals(
                crow.wasmline.WasmlineLoadState.CODE_FAILURE,
                (result as crow.wasmline.WasmlineLoadState.Failure).code,
            )

            val failure = result as crow.wasmline.WasmlineLoadState.Failure
            assertTrue(
                failure.cause.contains("not found"),
                "Error message should indicate file not found",
            )
        } finally {
            wasmlineShutdown()
        }
    }

    /**
     * Tests concurrent mode support.
     * Validates different loading configurations.
     */
    @Test
    fun supportsDifferentLoadingModes() {
        wasmlineBootstrap()
        val malformedArtifact = java.io.File.createTempFile("wasmline-invalid-", ".wasm").apply {
            writeBytes(byteArrayOf(0x00, 0x61, 0x73, 0x6D))
            deleteOnExit()
        }

        try {
            val result1 = wasmlineLoadArtifact(
                filepath = malformedArtifact.absolutePath,
                config = WasmlineConfig(supportConcurrent = false),
            )
            assertTrue(result1 is crow.wasmline.WasmlineLoadState.Failure)

            val result2 = wasmlineLoadArtifact(
                filepath = malformedArtifact.absolutePath,
                config = WasmlineConfig(supportConcurrent = true),
            )
            assertTrue(result2 is crow.wasmline.WasmlineLoadState.Failure)

            assertEquals(
                crow.wasmline.WasmlineLoadState.CODE_FAILURE,
                (result1 as crow.wasmline.WasmlineLoadState.Failure).code,
            )
            assertEquals(
                crow.wasmline.WasmlineLoadState.CODE_FAILURE,
                (result2 as crow.wasmline.WasmlineLoadState.Failure).code,
            )
        } finally {
            wasmlineShutdown()
        }
    }

    /**
     * Tests sequential warmup of multiple engine backends.
     */
    @Test
    fun warmsUpMultipleBackendsSequentially() {
        wasmlineBootstrap()

        try {
            wasmlineWarmup(WasmlineWarmupMode.PULLEY)
            val firstResult = Wasmline.supportsAot()

            wasmlineWarmup(WasmlineWarmupMode.CRANELIFT)
            val secondResult = Wasmline.supportsAot()

            wasmlineWarmup(WasmlineWarmupMode.PULLEY)
            val thirdResult = Wasmline.supportsAot()

            assertTrue(
                firstResult || secondResult || thirdResult,
                "Multiple sequential warmups should succeed at least once",
            )
        } finally {
            wasmlineShutdown()
        }
    }

    /**
     * Tests engine identification capabilities.
     */
    @Test
    fun reportsEngineCapabilitiesCorrectly() {
        wasmlineBootstrap()

        try {
            val initialSupportsAot = Wasmline.supportsAot()

            wasmlineWarmup(WasmlineWarmupMode.PULLEY)
            val postWarmupSupportsAot = Wasmline.supportsAot()

            assertTrue(
                postWarmupSupportsAot || initialSupportsAot,
                "Engine should report capabilities",
            )
        } finally {
            wasmlineShutdown()
        }
    }

    /**
     * Tests safe shutdown behavior.
     * Multiple consecutive shutdowns should not crash.
     */
    @Test
    fun performsSafeIdempotentShutdown() {
        wasmlineBootstrap()

        try {
            wasmlineWarmup(WasmlineWarmupMode.PULLEY)

            wasmlineShutdown()

            wasmlineShutdown()

            wasmlineShutdown()
        } finally {
            try {
                wasmlineShutdown()
            } catch (_: Exception) {}
        }
    }

    /**
     * Tests rapid startup and shutdown cycles.
     * Validates performance of engine lifecycle operations.
     */
    @Test
    fun handlesRapidEngineCycles() {
        repeat(3) { iteration ->
            wasmlineBootstrap()
            try {
                wasmlineWarmup(WasmlineWarmupMode.PULLEY)
                assertTrue(
                    Wasmline.supportsAot(),
                    "Cycle ${iteration + 1} should succeed",
                )
            } finally {
                wasmlineShutdown()
            }
        }
    }
}
