@file:Suppress("SpellCheckingInspection")

package crow.wasmline.test.wasmtime

import crow.wasmline.Wasmline
import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineLoadState
import crow.wasmline.WasmlineNativeBackend
import crow.wasmline.WasmlineWarmupMode
import crow.wasmline.wasmlineBootstrap
import crow.wasmline.wasmlineLoadArtifact
import crow.wasmline.wasmlineNativeRuntimeInfo
import crow.wasmline.wasmlineRuntimeCapabilities
import crow.wasmline.wasmlineShutdown
import crow.wasmline.wasmlineWarmup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

    @Test
    fun reportsLinkedRuntimeCapabilities() {
        val capabilities = wasmlineRuntimeCapabilities()
        val runtimeInfo = requireNotNull(wasmlineNativeRuntimeInfo())

        assertEquals("47.0.2", capabilities.wasmtimeVersion)
        assertEquals(capabilities.wasmtimeVersion, runtimeInfo.wasmtimeVersion)
        assertEquals(WasmlineNativeBackend.CRANELIFT, runtimeInfo.backend)
        assertTrue(capabilities.supportsCranelift)
        assertTrue(capabilities.supportsPulley)
        assertTrue(capabilities.targetOs.isNotBlank())
        assertTrue(capabilities.targetCpu.isNotBlank())
    }

    @Test
    fun rejectsInvalidArtifactFormatCodesAtTheJniBoundary() {
        wasmlineBootstrap()

        try {
            listOf(0, -1, 4).forEach { formatCode ->
                assertFalse(invokeNativeLoadAotWithFormatCode(formatCode))
            }
        } finally {
            wasmlineShutdown()
        }
    }

    @Test
    fun rejectsIncompatibleAotMetadataBeforeResolvingTheFile() {
        val capabilities = wasmlineRuntimeCapabilities()

        val result = wasmlineLoadArtifact(
            descriptor = WasmlineArtifactDescriptor(
                path = "/does/not/exist/plugin.cwasm",
                artifactFormat = WasmlineArtifactFormat.CWASM,
                targetCpu = capabilities.targetCpu,
                targetOs = capabilities.targetOs,
                targetCompilerVersion = "wasmtime-46.0.0",
                is64Bit = capabilities.is64Bit,
            ),
            config = WasmlineConfig(),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertTrue(failure.cause.contains("requires Wasmtime 46.0.0"))
    }

    private fun invokeNativeLoadAotWithFormatCode(formatCode: Int): Boolean {
        val intType = requireNotNull(Int::class.javaPrimitiveType)
        val method = Wasmline::class.java.getDeclaredMethod(
            "nativeLoadAotWithFormat",
            String::class.java,
            String::class.java,
            intType,
        )
        method.isAccessible = true
        return method.invoke(null, "invalid-format", "/does/not/exist/plugin.bin", formatCode) as Boolean
    }

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
     * Verifies that native direct-path loading fails before file resolution when it has no format.
     */
    @Test
    fun rejectsNativeDirectPathWithoutAnExplicitFormat() {
        wasmlineBootstrap()

        try {
            val result = wasmlineLoadArtifact(
                filepath = "/nonexistent/path/to/missing.cwasm",
                config = WasmlineConfig(supportConcurrent = false),
            )

            val failure = assertIs<WasmlineLoadState.Failure>(result)
            assertEquals(
                WasmlineLoadState.CODE_FAILURE,
                failure.code,
            )

            assertTrue(
                failure.cause.contains("requires an explicit artifactFormat"),
                "Error message should identify the missing native format",
            )
        } finally {
            wasmlineShutdown()
        }
    }

    @Test
    fun rejectsRawCoreArtifactsForBothLoadingModes() {
        wasmlineBootstrap()
        val rawArtifact = java.io.File.createTempFile("wasmline-raw-", ".wasm").apply {
            writeBytes(byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00))
            deleteOnExit()
        }

        try {
            val result1 = wasmlineLoadArtifact(
                descriptor = WasmlineArtifactDescriptor(
                    path = rawArtifact.absolutePath,
                    artifactFormat = WasmlineArtifactFormat.RAW_WASM,
                ),
                config = WasmlineConfig(supportConcurrent = false),
            )
            val failure1 = assertIs<WasmlineLoadState.Failure>(result1)

            val result2 = wasmlineLoadArtifact(
                descriptor = WasmlineArtifactDescriptor(
                    path = rawArtifact.absolutePath,
                    artifactFormat = WasmlineArtifactFormat.RAW_WASM,
                ),
                config = WasmlineConfig(supportConcurrent = true),
            )
            val failure2 = assertIs<WasmlineLoadState.Failure>(result2)

            assertEquals(
                WasmlineLoadState.CODE_FAILURE,
                failure1.code,
            )
            assertEquals(
                WasmlineLoadState.CODE_FAILURE,
                failure2.code,
            )
        } finally {
            wasmlineShutdown()
            rawArtifact.delete()
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
