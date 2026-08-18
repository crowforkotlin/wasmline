@file:Suppress("SpellCheckingInspection")

package crow.wasmline.test.wasmtime

import crow.wasmline.JniWasmlineBindings
import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineEngineKind
import crow.wasmline.WasmlineLoadState
import crow.wasmline.WasmlineNativeBackend
import crow.wasmline.WasmlineRuntime
import crow.wasmline.platformWasmlineLoadArtifact
import crow.wasmline.platformWasmlineRuntimeCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val INCOMPATIBLE_WASMTIME_VERSION = "0.0.0"

/**
 * Integration tests driving a real Wasmtime engine through Wasmline with native JNI bindings.
 *
 * These tests mirror [crow.wasmline.web.WebWasmRuntimeTest] but run against native Wasmtime
 * instead of browser's WebAssembly API. They validate core WASM functionality:
 * - Engine lifecycle management (preload/warm-up/shutdown)
 * - Multiple engine backend support (PULLEY/Cranelift)
 * - Resource cleanup and error handling
 *
 * Unlike webtest which uses hand-encoded wasm binaries, these tests use the full
 * Wasmline runtime pipeline including artifact loading and module caching.
 *
 * Author: crowforkotlin
 */
class NativeWasmtimeIntegrationTest {

    @Test
    fun reportsLinkedRuntimeCapabilities() {
        val capabilities = platformWasmlineRuntimeCapabilities()
        val runtimeInfo = requireNotNull(WasmlineRuntime.nativeInfo())

        assertEquals("47.0.2", capabilities.wasmtimeVersion)
        assertEquals(capabilities.wasmtimeVersion, runtimeInfo.wasmtimeVersion)
        assertEquals(WasmlineNativeBackend.CRANELIFT, runtimeInfo.backend)
        assertEquals(
            setOf(WasmlineEngineKind.PULLEY, WasmlineEngineKind.CRANELIFT),
            runtimeInfo.supportedEngines,
        )
        assertTrue(capabilities.supportsCranelift)
        assertTrue(capabilities.supportsPulley)
        assertTrue(capabilities.targetOs.isNotBlank())
        assertTrue(capabilities.targetCpu.isNotBlank())
    }

    @Test
    fun rejectsInvalidArtifactFormatCodesAtTheJniBoundary() {
        WasmlineRuntime.preload()

        try {
            listOf(0, -1, 4).forEach { formatCode ->
                assertFalse(invokeNativeLoadAotWithFormatCode(formatCode))
            }
        } finally {
            WasmlineRuntime.shutdown()
        }
    }

    @Test
    fun rejectsIncompatibleAotMetadataBeforeResolvingTheFile() {
        val capabilities = platformWasmlineRuntimeCapabilities()

        val result = platformWasmlineLoadArtifact(
            descriptor = WasmlineArtifactDescriptor(
                path = "/does/not/exist/plugin.cwasm",
                artifactFormat = WasmlineArtifactFormat.CWASM,
                targetCpu = capabilities.targetCpu,
                targetOs = capabilities.targetOs,
                targetCompilerVersion = "wasmtime-$INCOMPATIBLE_WASMTIME_VERSION",
                is64Bit = capabilities.is64Bit,
            ),
            config = WasmlineConfig(),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertTrue(failure.cause.contains("requires Wasmtime $INCOMPATIBLE_WASMTIME_VERSION"))
    }

    private fun invokeNativeLoadAotWithFormatCode(formatCode: Int): Boolean = JniWasmlineBindings.loadModuleWithFormatCode(
        key = "invalid-format",
        path = "/does/not/exist/plugin.bin",
        formatCode = formatCode,
    )

    /**
     * Tests that we can successfully preload the runtime and create the Wasmtime engine.
     * This is a positive test - validating successful initialization.
     */
    @Test
    fun createsAndInitializesEngineSuccessfully() {
        WasmlineRuntime.preload()

        try {
            WasmlineRuntime.warmUp(WasmlineEngineKind.PULLEY)

            assertTrue(
                WasmlineEngineKind.PULLEY in requireNotNull(WasmlineRuntime.nativeInfo()).supportedEngines,
                "The linked runtime should report Pulley support",
            )
        } finally {
            WasmlineRuntime.shutdown()
        }
    }

    /**
     * Tests Cranelift engine backend initialization.
     * Similar to PULLEY test but validates Cranelift path.
     */
    @Test
    fun initializesCraneliftBackendSuccessfully() {
        WasmlineRuntime.preload()

        try {
            WasmlineRuntime.warmUp(WasmlineEngineKind.CRANELIFT)

            assertTrue(
                WasmlineEngineKind.CRANELIFT in requireNotNull(WasmlineRuntime.nativeInfo()).supportedEngines,
                "The linked runtime should report Cranelift support",
            )
        } finally {
            WasmlineRuntime.shutdown()
        }
    }

    /**
     * Tests complete engine lifecycle from boot to shutdown.
     * Validates forward path execution.
     */
    @Test
    fun executesFullEngineLifecycle() {
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

    /**
     * Tests that engine resources are properly managed.
     * Positive validation of resource lifecycle.
     */
    @Test
    fun managesEngineResourcesCorrectly() {
        WasmlineRuntime.preload()

        try {
            WasmlineRuntime.warmUp(WasmlineEngineKind.PULLEY)
            assertTrue(WasmlineEngineKind.PULLEY in requireNotNull(WasmlineRuntime.nativeInfo()).supportedEngines)

            WasmlineRuntime.shutdown()

            WasmlineRuntime.preload()
            WasmlineRuntime.warmUp(WasmlineEngineKind.CRANELIFT)

            assertTrue(WasmlineEngineKind.CRANELIFT in requireNotNull(WasmlineRuntime.nativeInfo()).supportedEngines)
        } finally {
            try {
                WasmlineRuntime.shutdown()
            } catch (_: Exception) {}
        }
    }

    /**
     * Verifies that native direct-path loading fails before file resolution when it has no format.
     */
    @Test
    fun rejectsNativeDirectPathWithoutAnExplicitFormat() {
        WasmlineRuntime.preload()

        try {
            val result = platformWasmlineLoadArtifact(
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
            WasmlineRuntime.shutdown()
        }
    }

    @Test
    fun rejectsRawCoreArtifactsForBothLoadingModes() {
        WasmlineRuntime.preload()
        val rawArtifact = java.io.File.createTempFile("wasmline-raw-", ".wasm").apply {
            writeBytes(byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00))
            deleteOnExit()
        }

        try {
            val result1 = platformWasmlineLoadArtifact(
                descriptor = WasmlineArtifactDescriptor(
                    path = rawArtifact.absolutePath,
                    artifactFormat = WasmlineArtifactFormat.RAW_WASM,
                ),
                config = WasmlineConfig(supportConcurrent = false),
            )
            val failure1 = assertIs<WasmlineLoadState.Failure>(result1)

            val result2 = platformWasmlineLoadArtifact(
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
            WasmlineRuntime.shutdown()
            rawArtifact.delete()
        }
    }

    /**
     * Tests sequential warmup of multiple engine backends.
     */
    @Test
    fun warmsUpMultipleBackendsSequentially() {
        WasmlineRuntime.preload()

        try {
            WasmlineRuntime.warmUp(WasmlineEngineKind.PULLEY)
            WasmlineRuntime.warmUp(WasmlineEngineKind.CRANELIFT)
            WasmlineRuntime.warmUp(WasmlineEngineKind.PULLEY)

            assertEquals(
                setOf(WasmlineEngineKind.PULLEY, WasmlineEngineKind.CRANELIFT),
                requireNotNull(WasmlineRuntime.nativeInfo()).supportedEngines,
            )
        } finally {
            WasmlineRuntime.shutdown()
        }
    }

    /**
     * Tests engine identification capabilities.
     */
    @Test
    fun keepsRuntimeIdentityStableAcrossWarmUp() {
        WasmlineRuntime.preload()

        try {
            val initialInfo = requireNotNull(WasmlineRuntime.nativeInfo())

            WasmlineRuntime.warmUp(WasmlineEngineKind.PULLEY)

            assertEquals(initialInfo, WasmlineRuntime.nativeInfo())
        } finally {
            WasmlineRuntime.shutdown()
        }
    }

    /**
     * Tests safe shutdown behavior.
     * Multiple consecutive shutdowns should not crash.
     */
    @Test
    fun performsSafeIdempotentShutdown() {
        WasmlineRuntime.preload()

        try {
            WasmlineRuntime.warmUp(WasmlineEngineKind.PULLEY)

            WasmlineRuntime.shutdown()

            WasmlineRuntime.shutdown()

            WasmlineRuntime.shutdown()
        } finally {
            try {
                WasmlineRuntime.shutdown()
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
            WasmlineRuntime.preload()
            try {
                WasmlineRuntime.warmUp(WasmlineEngineKind.PULLEY)
                assertTrue(
                    WasmlineEngineKind.PULLEY in requireNotNull(WasmlineRuntime.nativeInfo()).supportedEngines,
                    "Cycle ${iteration + 1} should succeed",
                )
            } finally {
                WasmlineRuntime.shutdown()
            }
        }
    }
}
