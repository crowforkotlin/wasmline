package crow.wasmline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies native identity and exact AOT profile compatibility checks.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
class WasmlineRuntimeCapabilitiesTest {
    private val host = capabilities()

    @Test
    fun acceptsMatchingCoreAndComponentAotMetadata() {
        listOf(
            cwasmDescriptor(WasmlineExecutionModel.CORE_WASM),
            cwasmDescriptor(WasmlineExecutionModel.COMPONENT_MODEL),
            pwasmDescriptor(WasmlineExecutionModel.CORE_WASM),
            pwasmDescriptor(WasmlineExecutionModel.COMPONENT_MODEL),
        ).forEach { descriptor -> assertNull(descriptor.runtimeCompatibilityError(host)) }
    }

    @Test
    fun exposesImmutableRuntimeIdentityAndSupportedEngines() {
        val runtime = requireNotNull(host.nativeRuntimeInfo)

        assertEquals(WasmlineEngineKind.CRANELIFT, runtime.backend)
        assertEquals(setOf(WasmlineEngineKind.CRANELIFT, WasmlineEngineKind.PULLEY), runtime.supportedEngines)
        assertEquals(host.supportedArtifactFormats, runtime.supportedArtifactFormats)
        assertEquals(host.aotCompatibilityProfileIdsByBackend, runtime.aotCompatibilityProfileIdsByBackend)
        assertEquals("linux", runtime.operatingSystem)
        assertEquals("x86_64", runtime.architecture)
        assertEquals(64, runtime.pointerWidth)
    }

    @Test
    fun rejectsBridgeAbiAndReleaseMismatchBeforeArtifactLoading() {
        val bridgeFailure = assertFailsWith<IllegalStateException> {
            host.copy(nativeBridgeAbiVersion = WasmlineReleaseIdentity.NATIVE_BRIDGE_ABI_VERSION + 1)
                .validatedNativeIdentity()
        }
        assertTrue(bridgeFailure.message.orEmpty().contains("bridge ABI"))

        val releaseFailure = assertFailsWith<IllegalStateException> {
            host.copy(wasmlineReleaseVersion = "12.3.4").validatedNativeIdentity()
        }
        assertTrue(releaseFailure.message.orEmpty().contains("does not match Kotlin release"))
    }

    @Test
    fun rejectsMalformedOrInconsistentNativeAotIdentity() {
        val malformedProfile = assertFailsWith<IllegalStateException> {
            host.copy(
                aotCompatibilityProfileIdsByBackend = mapOf(
                    WasmlineEngineKind.CRANELIFT to setOf("invalid-profile-id"),
                    WasmlineEngineKind.PULLEY to setOf(PULLEY_PROFILE_ID),
                ),
            ).validatedNativeIdentity()
        }
        assertTrue(malformedProfile.message.orEmpty().contains("invalid AOT compatibility profile ID"))

        val missingPulleyProfile = assertFailsWith<IllegalStateException> {
            host.copy(
                aotCompatibilityProfileIdsByBackend = mapOf(
                    WasmlineEngineKind.CRANELIFT to setOf(CRANELIFT_PROFILE_ID),
                ),
            ).validatedNativeIdentity()
        }
        assertTrue(missingPulleyProfile.message.orEmpty().contains("PWASM without a Pulley"))

        val pulleyWithCwasm = assertFailsWith<IllegalStateException> {
            host.copy(backend = WasmlineEngineKind.PULLEY).validatedNativeIdentity()
        }
        assertTrue(pulleyWithCwasm.message.orEmpty().contains("PWASM-only"))
    }

    @Test
    fun rejectsMissingAndDifferentBackendProfileIds() {
        assertEquals(
            "AOT artifacts require aotCompatibilityProfileId metadata.",
            cwasmDescriptor().copy(aotCompatibilityProfileId = null).runtimeCompatibilityError(host),
        )
        assertEquals(
            "CWASM profile '$OTHER_PROFILE_ID' is not supported by the linked CRANELIFT runtime.",
            cwasmDescriptor().copy(aotCompatibilityProfileId = OTHER_PROFILE_ID).runtimeCompatibilityError(host),
        )
        assertEquals(
            "PWASM profile '$CRANELIFT_PROFILE_ID' is not supported by the linked PULLEY runtime.",
            pwasmDescriptor().copy(aotCompatibilityProfileId = CRANELIFT_PROFILE_ID).runtimeCompatibilityError(host),
        )
    }

    @Test
    fun rejectsUnsupportedFormatsAndWrongNativeTargets() {
        assertEquals(
            "The native runtime does not support artifact format CWASM.",
            cwasmDescriptor().runtimeCompatibilityError(
                host.copy(supportedArtifactFormats = setOf(WasmlineArtifactFormat.PWASM)),
            ),
        )
        assertEquals(
            "CWASM target linux/aarch64/64 does not match native runtime linux/x86_64/64.",
            cwasmDescriptor().copy(architecture = "aarch64").runtimeCompatibilityError(host),
        )
        assertEquals(
            "PWASM target pulley32/32 does not match native runtime pointer width 64.",
            pwasmDescriptor().copy(architecture = "pulley32", pointerWidth = 32).runtimeCompatibilityError(host),
        )
        assertEquals(
            "CWASM CPU feature profile 'custom-v1' is not supported by the native runtime.",
            cwasmDescriptor().copy(cpuFeatureProfile = "custom-v1").runtimeCompatibilityError(host),
        )
    }

    @Test
    fun preservesUnspecifiedAndRawDescriptorBehavior() {
        assertNull(WasmlineArtifactDescriptor(path = "unspecified.cwasm").runtimeCompatibilityError(host))
        assertNull(
            WasmlineArtifactDescriptor(
                path = "browser.wasm",
                artifactFormat = WasmlineArtifactFormat.RAW_WASM,
            ).runtimeCompatibilityError(host),
        )
    }

    private fun capabilities(): WasmlineRuntimeCapabilities = WasmlineRuntimeCapabilities(
        backend = WasmlineEngineKind.CRANELIFT,
        supportedArtifactFormats = setOf(WasmlineArtifactFormat.CWASM, WasmlineArtifactFormat.PWASM),
        wasmtimeVersion = "12.3.4",
        aotCompatibilityProfileIdsByBackend = mapOf(
            WasmlineEngineKind.CRANELIFT to setOf(CRANELIFT_PROFILE_ID),
            WasmlineEngineKind.PULLEY to setOf(PULLEY_PROFILE_ID),
        ),
        nativeBridgeAbiVersion = WasmlineReleaseIdentity.NATIVE_BRIDGE_ABI_VERSION,
        wasmlineReleaseVersion = WasmlineReleaseIdentity.RELEASE_VERSION,
        operatingSystem = "linux",
        architecture = "x86_64",
        pointerWidth = 64,
        supportedCpuFeatureProfiles = setOf("baseline-v1"),
    )

    private fun cwasmDescriptor(executionModel: WasmlineExecutionModel = WasmlineExecutionModel.CORE_WASM): WasmlineArtifactDescriptor =
        aotDescriptor(WasmlineArtifactFormat.CWASM, executionModel).copy(
            operatingSystem = "linux",
            architecture = "x86_64",
            pointerWidth = 64,
            cpuFeatureProfile = "baseline-v1",
            aotCompatibilityProfileId = CRANELIFT_PROFILE_ID,
        )

    private fun pwasmDescriptor(executionModel: WasmlineExecutionModel = WasmlineExecutionModel.CORE_WASM): WasmlineArtifactDescriptor =
        aotDescriptor(WasmlineArtifactFormat.PWASM, executionModel).copy(
            architecture = "pulley64",
            pointerWidth = 64,
            aotCompatibilityProfileId = PULLEY_PROFILE_ID,
        )

    private fun aotDescriptor(format: WasmlineArtifactFormat, executionModel: WasmlineExecutionModel): WasmlineArtifactDescriptor {
        val component = executionModel == WasmlineExecutionModel.COMPONENT_MODEL
        return WasmlineArtifactDescriptor(
            path = if (format == WasmlineArtifactFormat.CWASM) "plugin.cwasm" else "plugin.pwasm",
            artifactFormat = format,
            executionModel = executionModel,
            invocationProtocol = if (component) {
                WasmlineInvocationProtocol.COMPONENT_EXPORT
            } else {
                WasmlineInvocationProtocol.WASMLINE_SERVICE
            },
            exportName = if (component) "plugin/invoke" else null,
        )
    }

    /**
     * Defines compatibility profile IDs used by runtime capability fixtures.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    private companion object {
        const val CRANELIFT_PROFILE_ID = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
        const val PULLEY_PROFILE_ID = "sha256:2222222222222222222222222222222222222222222222222222222222222222"
        const val OTHER_PROFILE_ID = "sha256:3333333333333333333333333333333333333333333333333333333333333333"
    }
}
