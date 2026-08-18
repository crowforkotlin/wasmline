package crow.wasmline.test.wasmtime

import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineComponentCallResult
import crow.wasmline.WasmlineComponentValue
import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineEngineKind
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.WasmlineLoadState
import crow.wasmline.WasmlineRawCallResult
import crow.wasmline.WasmlineRawValue
import crow.wasmline.WasmlineRuntime
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invokeComponentResult
import crow.wasmline.invokeRawResult
import crow.wasmline.platformWasmlineLoadArtifact
import crow.wasmline.platformWasmlineRuntimeCapabilities
import crow.wasmline.wasmlineAotLoadPathDiagnostics
import crow.wasmline.wasmlineResetAotLoadPathDiagnostics
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Tests direct typed Component AOT calls and native raw artifact boundaries.
 *
 * Verifies direct typed Component AOT calls and native raw Core/Component rejection boundaries.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
class NativeDirectInvocationTest {

    @Test
    fun rawExportFixturesRequirePrecompiledAotSuffixes() {
        assertEquals(WasmlineArtifactFormat.CWASM, coreAotFormat("fixture.cwasm"))
        assertEquals(WasmlineArtifactFormat.PWASM, coreAotFormat("fixture.pwasm"))
        assertFailsWith<IllegalArgumentException> {
            coreAotFormat("fixture.wasm")
        }
    }

    @Test
    fun rawExportAotFixtureReturnsValuesAndRecoverableFailures() {
        if (!liveTestsEnabled()) return

        val artifact = copyAotCoreFixture()
        try {
            val handle = loadAotCore(artifact)
            try {
                val success = assertIs<WasmlineCallResult.Success<WasmlineRawCallResult>>(
                    handle.invokeRawResult(
                        exportName = "add",
                        arguments = listOf(WasmlineRawValue.I32(2), WasmlineRawValue.I32(3)),
                    ),
                )
                assertEquals(listOf(WasmlineRawValue.I32(5)), success.value.values)

                val typeFailure = assertIs<WasmlineCallResult.Failure>(
                    handle.invokeRawResult(
                        exportName = "add",
                        arguments = listOf(WasmlineRawValue.I64(2), WasmlineRawValue.I64(3)),
                    ),
                )
                assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, typeFailure.error.code)

                val missingFailure = assertIs<WasmlineCallResult.Failure>(
                    handle.invokeRawResult(exportName = "missing"),
                )
                assertEquals(WasmlineErrorCode.CORE_EXPORT_NOT_FOUND, missingFailure.error.code)

                val trapFailure = assertIs<WasmlineCallResult.Failure>(
                    handle.invokeRawResult(exportName = "trap"),
                )
                assertEquals(WasmlineErrorCode.CORE_TRAP, trapFailure.error.code)
            } finally {
                handle.close()
            }
        } finally {
            WasmlineRuntime.shutdown()
            artifact.delete()
        }
    }

    @Test
    fun conflictingWarmUpPreservesTheLoadedArtifact() {
        if (!liveTestsEnabled()) return

        val artifact = copyAotCoreFixture()
        val artifactFormat = coreAotFormat(artifact.name)
        val conflictingEngine = when (artifactFormat) {
            WasmlineArtifactFormat.CWASM -> WasmlineEngineKind.PULLEY
            WasmlineArtifactFormat.PWASM -> WasmlineEngineKind.CRANELIFT
            WasmlineArtifactFormat.RAW_WASM -> error("A live native fixture must be precompiled.")
        }
        try {
            val handle = loadAotCore(artifact)
            try {
                assertFailsWith<IllegalStateException> {
                    WasmlineRuntime.warmUp(conflictingEngine)
                }

                val result = assertIs<WasmlineCallResult.Success<WasmlineRawCallResult>>(
                    handle.invokeRawResult(
                        exportName = "add",
                        arguments = listOf(WasmlineRawValue.I32(2), WasmlineRawValue.I32(3)),
                    ),
                )
                assertEquals(listOf(WasmlineRawValue.I32(5)), result.value.values)
            } finally {
                handle.close()
            }

            WasmlineRuntime.warmUp(conflictingEngine)
        } finally {
            WasmlineRuntime.shutdown()
            artifact.delete()
        }
    }

    @Test
    fun directTypedComponentFixturesRequirePrecompiledAotSuffixes() {
        assertEquals(WasmlineArtifactFormat.CWASM, componentAotFormat("fixture.cwasm"))
        assertEquals(WasmlineArtifactFormat.PWASM, componentAotFormat("fixture.pwasm"))
        assertFailsWith<IllegalArgumentException> {
            componentAotFormat("fixture.wasm")
        }
    }

    @Test
    fun componentAotExportLoadsWithoutWitAndConvertsValues() {
        if (!liveTestsEnabled()) return

        val artifact = copyAotComponentFixture()
        try {
            val handle = loadAotComponent(artifact)
            try {
                val success = assertIs<WasmlineCallResult.Success<WasmlineComponentCallResult>>(
                    handle.invokeComponentResult(
                        exportName = "add",
                        arguments = listOf(WasmlineComponentValue.S32(2), WasmlineComponentValue.S32(3)),
                    ),
                )
                assertEquals(listOf(WasmlineComponentValue.S32(5)), success.value.values)

                val typeFailure = assertIs<WasmlineCallResult.Failure>(
                    handle.invokeComponentResult(
                        exportName = "add",
                        arguments = listOf(WasmlineComponentValue.StringValue("2"), WasmlineComponentValue.S32(3)),
                    ),
                )
                assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, typeFailure.error.code)

                val missingFailure = assertIs<WasmlineCallResult.Failure>(
                    handle.invokeComponentResult(exportName = "missing"),
                )
                assertEquals(WasmlineErrorCode.COMPONENT_EXPORT_NOT_FOUND, missingFailure.error.code)

                val trapFailure = assertIs<WasmlineCallResult.Failure>(
                    handle.invokeComponentResult(exportName = "trap"),
                )
                assertEquals(WasmlineErrorCode.COMPONENT_TRAP, trapFailure.error.code)
            } finally {
                handle.close()
            }
        } finally {
            WasmlineRuntime.shutdown()
            artifact.delete()
        }
    }

    @Test
    fun successfulCoreAndComponentAotLoadsUseDeserializeWithoutRawCompilation() {
        if (!liveTestsEnabled()) return

        val coreArtifact = copyAotCoreFixture()
        val componentArtifact = copyAotComponentFixture()
        try {
            WasmlineRuntime.shutdown()
            wasmlineResetAotLoadPathDiagnostics()

            val core = loadAotCore(coreArtifact)
            try {
                assertIs<WasmlineCallResult.Success<WasmlineRawCallResult>>(
                    core.invokeRawResult(
                        exportName = "add",
                        arguments = listOf(WasmlineRawValue.I32(2), WasmlineRawValue.I32(3)),
                    ),
                )
            } finally {
                core.close()
            }

            val component = loadAotComponent(componentArtifact)
            try {
                assertIs<WasmlineCallResult.Success<WasmlineComponentCallResult>>(
                    component.invokeComponentResult(
                        exportName = "add",
                        arguments = listOf(WasmlineComponentValue.S32(2), WasmlineComponentValue.S32(3)),
                    ),
                )
            } finally {
                component.close()
            }

            val diagnostics = wasmlineAotLoadPathDiagnostics()
            assertEquals(1, diagnostics.coreDeserializeSuccesses)
            assertEquals(1, diagnostics.componentDeserializeSuccesses)
            assertEquals(0, diagnostics.moduleNewCalls)
            assertEquals(0, diagnostics.componentNewCalls)
        } finally {
            WasmlineRuntime.shutdown()
            coreArtifact.delete()
            componentArtifact.delete()
        }
    }

    @Test
    fun rawCoreArtifactIsRejectedByBothNativeLoadingModes() {
        val artifact = createRawFixture()
        try {
            assertRawNativeArtifactIsRejectedByBothLoadingModes(
                WasmlineArtifactDescriptor(
                    path = artifact.absolutePath,
                    artifactFormat = WasmlineArtifactFormat.RAW_WASM,
                    executionModel = WasmlineExecutionModel.CORE_WASM,
                    invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
                    exportName = "add",
                ),
            )
        } finally {
            WasmlineRuntime.shutdown()
            artifact.delete()
        }
    }

    @Test
    fun coreCwasmFormatDoesNotCompileRawWasmBytes() {
        val artifact = createRawFixture()
        val runtime = platformWasmlineRuntimeCapabilities()
        try {
            val state = platformWasmlineLoadArtifact(
                descriptor = WasmlineArtifactDescriptor(
                    path = artifact.absolutePath,
                    artifactFormat = WasmlineArtifactFormat.CWASM,
                    targetCpu = runtime.targetCpu,
                    targetOs = runtime.targetOs,
                    targetCompilerVersion = "wasmtime-${runtime.wasmtimeVersion}",
                    is64Bit = runtime.is64Bit,
                    executionModel = WasmlineExecutionModel.CORE_WASM,
                    invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
                    exportName = "add",
                ),
                config = WasmlineConfig(supportConcurrent = false),
            )
            assertIs<WasmlineLoadState.Failure>(state)
        } finally {
            WasmlineRuntime.shutdown()
            artifact.delete()
        }
    }

    @Test
    fun rawComponentArtifactIsRejectedByBothNativeLoadingModes() {
        val artifact = copyComponentFixture()
        try {
            assertRawNativeArtifactIsRejectedByBothLoadingModes(
                WasmlineArtifactDescriptor(
                    path = artifact.absolutePath,
                    artifactFormat = WasmlineArtifactFormat.RAW_WASM,
                    executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
                    invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
                    exportName = "add",
                ),
            )
        } finally {
            WasmlineRuntime.shutdown()
            artifact.delete()
        }
    }

    @Test
    fun componentCwasmFormatDoesNotCompileRawWasmBytes() {
        val artifact = copyComponentFixture()
        try {
            val state = platformWasmlineLoadArtifact(
                descriptor = componentAotDescriptor(
                    path = artifact.absolutePath,
                    artifactFormat = WasmlineArtifactFormat.CWASM,
                ),
                config = WasmlineConfig(supportConcurrent = false),
            )
            assertIs<WasmlineLoadState.Failure>(state)
        } finally {
            WasmlineRuntime.shutdown()
            artifact.delete()
        }
    }

    private fun loadAotComponent(artifact: File): crow.wasmline.Wasmline {
        val state = platformWasmlineLoadArtifact(
            descriptor = componentAotDescriptor(
                path = artifact.absolutePath,
                artifactFormat = componentAotFormat(artifact.name),
            ),
            config = WasmlineConfig(supportConcurrent = false),
        )
        return assertIs<WasmlineLoadState.Success>(state).wasmline
    }

    private fun loadAotCore(artifact: File): crow.wasmline.Wasmline {
        val state = platformWasmlineLoadArtifact(
            descriptor = coreAotDescriptor(
                path = artifact.absolutePath,
                artifactFormat = coreAotFormat(artifact.name),
            ),
            config = WasmlineConfig(supportConcurrent = false),
        )
        return assertIs<WasmlineLoadState.Success>(state).wasmline
    }

    private fun componentAotDescriptor(path: String, artifactFormat: WasmlineArtifactFormat): WasmlineArtifactDescriptor {
        val runtime = platformWasmlineRuntimeCapabilities()
        return WasmlineArtifactDescriptor(
            path = path,
            artifactFormat = artifactFormat,
            targetCpu = targetCpuFor(artifactFormat, runtime.is64Bit, runtime.targetCpu),
            targetOs = targetOsFor(artifactFormat, runtime.targetOs),
            targetCompilerVersion = "wasmtime-${runtime.wasmtimeVersion}",
            is64Bit = runtime.is64Bit,
            executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
            invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
            exportName = "add",
        )
    }

    private fun coreAotDescriptor(path: String, artifactFormat: WasmlineArtifactFormat): WasmlineArtifactDescriptor {
        val runtime = platformWasmlineRuntimeCapabilities()
        return WasmlineArtifactDescriptor(
            path = path,
            artifactFormat = artifactFormat,
            targetCpu = targetCpuFor(artifactFormat, runtime.is64Bit, runtime.targetCpu),
            targetOs = targetOsFor(artifactFormat, runtime.targetOs),
            targetCompilerVersion = "wasmtime-${runtime.wasmtimeVersion}",
            is64Bit = runtime.is64Bit,
            executionModel = WasmlineExecutionModel.CORE_WASM,
            invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
            exportName = "add",
        )
    }

    private fun assertRawNativeArtifactIsRejectedByBothLoadingModes(descriptor: WasmlineArtifactDescriptor) {
        listOf(false, true).forEach { supportConcurrent ->
            val state = platformWasmlineLoadArtifact(
                descriptor = descriptor,
                config = WasmlineConfig(supportConcurrent = supportConcurrent),
            )
            val failure = assertIs<WasmlineLoadState.Failure>(state)
            assertEquals(WasmlineLoadState.CODE_FAILURE, failure.code)
        }
    }

    private fun createRawFixture(): File = File.createTempFile("wasmline-raw-export-", ".wasm").apply {
        writeBytes(
            byteArrayOf(
                0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
                0x01, 0x0B, 0x02, 0x60, 0x02, 0x7F, 0x7F, 0x01, 0x7F, 0x60, 0x00, 0x01, 0x7F,
                0x03, 0x03, 0x02, 0x00, 0x01,
                0x07, 0x0E, 0x02, 0x03, 0x61, 0x64, 0x64, 0x00, 0x00, 0x04, 0x74, 0x72, 0x61, 0x70, 0x00, 0x01,
                0x0A, 0x0D, 0x02, 0x07, 0x00, 0x20, 0x00, 0x20, 0x01, 0x6A, 0x0B, 0x03, 0x00, 0x00, 0x0B,
            ),
        )
        deleteOnExit()
    }

    private fun copyComponentFixture(): File {
        val destination = File.createTempFile("wasmline-component-export-", ".wasm")
        NativeDirectInvocationTest::class.java.getResourceAsStream("/fixtures/component-export.wasm").use { input ->
            requireNotNull(input) { "Component fixture resource is missing." }
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        destination.deleteOnExit()
        return destination
    }

    private fun copyAotComponentFixture(): File {
        val source = requireNotNull(System.getenv(DIRECT_COMPONENT_AOT_FIXTURE_ENV)) {
            "$DIRECT_COMPONENT_AOT_FIXTURE_ENV must be set when $LIVE_TESTS_ENV=1."
        }.let(::File)
        require(source.isFile) { "$DIRECT_COMPONENT_AOT_FIXTURE_ENV does not point to a file: ${source.absolutePath}" }
        val destination = File.createTempFile("wasmline-component-direct-", componentAotFormat(source.name).fileSuffix())
        source.copyTo(destination, overwrite = true)
        destination.deleteOnExit()
        return destination
    }

    private fun copyAotCoreFixture(): File {
        val source = requireNotNull(System.getenv(RAW_EXPORT_AOT_FIXTURE_ENV)) {
            "$RAW_EXPORT_AOT_FIXTURE_ENV must be set when $LIVE_TESTS_ENV=1."
        }.let(::File)
        require(source.isFile) { "$RAW_EXPORT_AOT_FIXTURE_ENV does not point to a file: ${source.absolutePath}" }
        val destination = File.createTempFile("wasmline-raw-export-aot-", coreAotFormat(source.name).fileSuffix())
        source.copyTo(destination, overwrite = true)
        destination.deleteOnExit()
        return destination
    }

    private fun componentAotFormat(filename: String): WasmlineArtifactFormat = when {
        filename.endsWith(".cwasm", ignoreCase = true) -> WasmlineArtifactFormat.CWASM

        filename.endsWith(".pwasm", ignoreCase = true) -> WasmlineArtifactFormat.PWASM

        else -> throw IllegalArgumentException(
            "Direct Component fixture must be a precompiled .cwasm or .pwasm artifact, not '$filename'.",
        )
    }

    private fun coreAotFormat(filename: String): WasmlineArtifactFormat = when {
        filename.endsWith(".cwasm", ignoreCase = true) -> WasmlineArtifactFormat.CWASM

        filename.endsWith(".pwasm", ignoreCase = true) -> WasmlineArtifactFormat.PWASM

        else -> throw IllegalArgumentException(
            "Core RAW_EXPORT fixture must be a precompiled .cwasm or .pwasm artifact, not '$filename'.",
        )
    }

    private fun WasmlineArtifactFormat.fileSuffix(): String = when (this) {
        WasmlineArtifactFormat.CWASM -> ".cwasm"
        WasmlineArtifactFormat.PWASM -> ".pwasm"
        WasmlineArtifactFormat.RAW_WASM -> error("Direct Component fixtures cannot use raw Wasm.")
    }

    private fun targetCpuFor(artifactFormat: WasmlineArtifactFormat, is64Bit: Boolean, runtimeCpu: String): String = when (artifactFormat) {
        WasmlineArtifactFormat.CWASM -> runtimeCpu
        WasmlineArtifactFormat.PWASM -> if (is64Bit) "pulley64" else "pulley32"
        WasmlineArtifactFormat.RAW_WASM -> error("Direct Component fixtures cannot use raw Wasm.")
    }

    private fun targetOsFor(artifactFormat: WasmlineArtifactFormat, runtimeOs: String): String? = when (artifactFormat) {
        WasmlineArtifactFormat.CWASM -> runtimeOs
        WasmlineArtifactFormat.PWASM -> null
        WasmlineArtifactFormat.RAW_WASM -> error("Direct Component fixtures cannot use raw Wasm.")
    }

    private fun liveTestsEnabled(): Boolean = System.getenv(LIVE_TESTS_ENV) == "1"

    private companion object {
        const val LIVE_TESTS_ENV = "WASMLINE_LIVE_TESTS"
        const val DIRECT_COMPONENT_AOT_FIXTURE_ENV = "WASMLINE_TEST_DIRECT_COMPONENT_AOT"
        const val RAW_EXPORT_AOT_FIXTURE_ENV = "WASMLINE_TEST_RAW_EXPORT_AOT"
    }
}
