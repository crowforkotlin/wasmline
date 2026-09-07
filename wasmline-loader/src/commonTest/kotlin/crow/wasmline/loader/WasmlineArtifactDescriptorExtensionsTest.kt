package crow.wasmline.loader

import crow.wasmline.RawAbiMetadata
import crow.wasmline.RawExport
import crow.wasmline.RawExportKind
import crow.wasmline.RawFunctionSignature
import crow.wasmline.RawValueType
import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.internal.WasmlineArtifactSelection
import crow.wasmline.loader.model.WasmlineArtifactTarget
import crow.wasmline.loader.model.WasmlineArtifactVariant
import crow.wasmline.loader.model.WasmlineRuntimeContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies conversion from a selected manifest variant to a runtime descriptor.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
class WasmlineArtifactDescriptorExtensionsTest {
    @Test
    fun mapsPhysicalTargetProfileAndRuntimeContractIndependently() {
        val rawAbi = RawAbiMetadata(
            exports = listOf(
                RawExport(
                    name = "add",
                    kind = RawExportKind.FUNCTION,
                    signature = RawFunctionSignature(
                        parameters = listOf(RawValueType.I32, RawValueType.I32),
                        results = listOf(RawValueType.I32),
                    ),
                ),
            ),
        )
        val selection = WasmlineArtifactSelection.Selected(
            target = WasmlineArtifactTarget(
                format = WasmlineArtifactFormat.CWASM,
                operatingSystem = "linux",
                architecture = "x86_64",
                pointerWidth = 64,
                cpuFeatureProfile = "baseline-v1",
                variants = listOf(WasmlineArtifactVariant(listOf(PROFILE_ID), DIGEST, 3)),
            ),
            variant = WasmlineArtifactVariant(listOf(PROFILE_ID), DIGEST, 3),
            matchedAotCompatibilityProfileId = PROFILE_ID,
        )
        val descriptor = selection.toDescriptor(
            path = "/package/plugin.cwasm",
            contract = WasmlineRuntimeContract(
                executionModel = WasmlineExecutionModel.CORE_WASM,
                invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
                contractMetadata = mapOf("abi" to "scalar"),
                rawAbi = rawAbi,
            ),
        )

        assertEquals(WasmlineArtifactFormat.CWASM, descriptor.artifactFormat)
        assertEquals("linux", descriptor.operatingSystem)
        assertEquals("x86_64", descriptor.architecture)
        assertEquals(64, descriptor.pointerWidth)
        assertEquals("baseline-v1", descriptor.cpuFeatureProfile)
        assertEquals(PROFILE_ID, descriptor.aotCompatibilityProfileId)
        assertEquals(WasmlineInvocationProtocol.RAW_EXPORT, descriptor.invocationProtocol)
        assertEquals(rawAbi, descriptor.rawAbi)
        assertNull(descriptor.exportName)
        assertNull(descriptor.validationError())
    }

    /**
     * Defines immutable descriptor conversion fixture identities.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    private companion object {
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val PROFILE_ID = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
