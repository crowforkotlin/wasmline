package crow.wasmline

import crow.wasmline.invocation.WasmlineCallResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies a generated Pulley RAW_EXPORT fixture on the iOS simulator runtime.
 *
 * Date: 2026-09-01
 * Author: crowforkotlin
 */
class WasmlineIosRawExportAotTest {

    @Test
    fun generatedPwasmFixtureSupportsScalarValuesAndResultShapes() {
        val runtime = platformWasmlineRuntimeCapabilities()
        require(runtime.pointerWidth == 64) { "iOS native AOT tests require a 64-bit Pulley runtime." }
        val profileId = runtime.aotCompatibilityProfileIdsByBackend[WasmlineEngineKind.PULLEY]
            ?.singleOrNull()
            ?: error("The iOS runtime must report exactly one Pulley AOT compatibility profile.")
        val fixturePath = NativeIosFixtureCatalog.requirePwasmPath(
            fixtureId = "raw-export-basic",
            profileId = profileId,
            executionModel = WasmlineExecutionModel.CORE_WASM,
            invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
        )
        val handle = assertIs<WasmlineLoadState.Success>(
            platformWasmlineLoadArtifact(
                descriptor = WasmlineArtifactDescriptor(
                    path = fixturePath,
                    artifactFormat = WasmlineArtifactFormat.PWASM,
                    architecture = "pulley${runtime.pointerWidth}",
                    pointerWidth = runtime.pointerWidth,
                    aotCompatibilityProfileId = profileId,
                    executionModel = WasmlineExecutionModel.CORE_WASM,
                    invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
                    exportName = "add",
                ),
                config = WasmlineConfig(supportConcurrent = false),
            ),
        ).wasmline
        try {
            assertEquals(
                listOf(RawValue.I32(42)),
                invokeRawValues(handle, "add", RawValue.I32(19), RawValue.I32(23)),
            )
            assertEquals(
                listOf(RawValue.I64(2_999_999_999L)),
                invokeRawValues(handle, "add64", RawValue.I64(3_000_000_000L), RawValue.I64(-1L)),
            )
            assertEquals(
                listOf(RawValue.F32(-1.25f)),
                invokeRawValues(handle, "neg_f32", RawValue.F32(1.25f)),
            )
            assertEquals(
                listOf(RawValue.I32(7), RawValue.I64(42L)),
                invokeRawValues(handle, "pair", RawValue.I32(7)),
            )
            assertEquals(emptyList(), invokeRawValues(handle, "void", RawValue.I32(0)))

            val f64 = invokeRawValues(handle, "neg_f64", RawValue.F64(-0.0)).single() as RawValue.F64
            assertEquals(0.0, f64.value)
            assertEquals(0.0.toBits(), f64.value.toBits())
            val nan = invokeRawValues(handle, "neg_f32", RawValue.F32(Float.NaN)).single() as RawValue.F32
            assertTrue(nan.value.isNaN())
        } finally {
            handle.close()
            WasmlineRuntime.shutdown()
        }
    }

    /** Invokes a direct raw export and returns its successful values. */
    private fun invokeRawValues(handle: Wasmline, exportName: String, vararg arguments: RawValue): List<RawValue> =
        assertIs<WasmlineCallResult.Success<WasmlineRawCallResult>>(
            handle.invokeRawResult(exportName, arguments.toList()),
        ).value.values
}
