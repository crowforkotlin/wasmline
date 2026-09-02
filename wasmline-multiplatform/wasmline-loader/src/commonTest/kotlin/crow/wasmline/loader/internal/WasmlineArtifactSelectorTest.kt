package crow.wasmline.loader.internal

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineEngineKind
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.WasmlineNativeRuntimeInfo
import crow.wasmline.loader.model.WasmlineAotCompatibilityProfile
import crow.wasmline.loader.model.WasmlineArtifactTarget
import crow.wasmline.loader.model.WasmlineArtifactVariant
import crow.wasmline.loader.model.WasmlineManifest
import crow.wasmline.loader.model.WasmlineRuntimeContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Verifies deterministic browser, Cranelift, and Pulley artifact selection.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
class WasmlineArtifactSelectorTest {
    @Test
    fun browserSelectsOnlyRawWasm() {
        val selected = assertIs<WasmlineArtifactSelection.Selected>(
            WasmlineArtifactSelector.select(
                manifest(targets = listOf(rawTarget(), pulleyTarget(PULLEY_PROFILE_ID))),
                WasmlineHostArtifactTarget(
                    operatingSystem = "browser",
                    architecture = "wasm32",
                    pointerWidth = 32,
                    supportedArtifactFormats = setOf(WasmlineArtifactFormat.RAW_WASM),
                ),
            ),
        )

        assertEquals(WasmlineArtifactFormat.RAW_WASM, selected.target.format)
        assertEquals(RAW_DIGEST, selected.variant.sha256)
    }

    @Test
    fun craneliftPrefersExactCwasmProfile() {
        val selected = assertIs<WasmlineArtifactSelection.Selected>(
            WasmlineArtifactSelector.select(
                manifest(targets = listOf(pulleyTarget(PULLEY_PROFILE_ID), cwasmTarget(CRANELIFT_PROFILE_ID))),
                nativeHost(
                    formats = setOf(WasmlineArtifactFormat.CWASM, WasmlineArtifactFormat.PWASM),
                    profiles = mapOf(
                        WasmlineEngineKind.CRANELIFT to setOf(CRANELIFT_PROFILE_ID),
                        WasmlineEngineKind.PULLEY to setOf(PULLEY_PROFILE_ID),
                    ),
                ),
            ),
        )

        assertEquals(WasmlineArtifactFormat.CWASM, selected.target.format)
        assertEquals(CRANELIFT_PROFILE_ID, selected.matchedAotCompatibilityProfileId)
    }

    @Test
    fun craneliftFallsBackToPulleyOnlyWithExactPulleyProfile() {
        val selected = assertIs<WasmlineArtifactSelection.Selected>(
            WasmlineArtifactSelector.select(
                manifest(targets = listOf(cwasmTarget(OTHER_CRANELIFT_PROFILE_ID), pulleyTarget(PULLEY_PROFILE_ID))),
                nativeHost(
                    formats = setOf(WasmlineArtifactFormat.CWASM, WasmlineArtifactFormat.PWASM),
                    profiles = mapOf(
                        WasmlineEngineKind.CRANELIFT to setOf(CRANELIFT_PROFILE_ID),
                        WasmlineEngineKind.PULLEY to setOf(PULLEY_PROFILE_ID),
                    ),
                ),
            ),
        )

        assertEquals(WasmlineArtifactFormat.PWASM, selected.target.format)
        assertEquals(PULLEY_PROFILE_ID, selected.matchedAotCompatibilityProfileId)
    }

    @Test
    fun neverUsesCraneliftProfileForPulleyArtifact() {
        val selection = WasmlineArtifactSelector.select(
            manifest(targets = listOf(pulleyTarget(CRANELIFT_PROFILE_ID))),
            nativeHost(
                formats = setOf(WasmlineArtifactFormat.PWASM),
                profiles = mapOf(WasmlineEngineKind.CRANELIFT to setOf(CRANELIFT_PROFILE_ID)),
            ),
        )

        assertIs<WasmlineArtifactSelection.NotCompatible>(selection)
    }

    @Test
    fun rejectsTwoEquallyCompatibleVariants() {
        val target = pulleyTarget(PULLEY_PROFILE_ID).copy(
            variants = listOf(
                variant(PULLEY_PROFILE_ID, PULLEY_DIGEST),
                variant(SECOND_PULLEY_PROFILE_ID, SECOND_PULLEY_DIGEST),
            ),
        )
        val selection = WasmlineArtifactSelector.select(
            manifest(targets = listOf(target)),
            nativeHost(
                formats = setOf(WasmlineArtifactFormat.PWASM),
                profiles = mapOf(
                    WasmlineEngineKind.PULLEY to setOf(PULLEY_PROFILE_ID, SECOND_PULLEY_PROFILE_ID),
                ),
            ),
        )

        assertIs<WasmlineArtifactSelection.Invalid>(selection)
    }

    private fun manifest(targets: List<WasmlineArtifactTarget>): WasmlineManifest = WasmlineManifest(
        pluginId = "crow.wasmline.selector",
        version = "1.0.0",
        versionCode = 1,
        minSdkVersion = "1.0.0",
        buildTimestamp = 0,
        runtimeContract = WasmlineRuntimeContract(
            WasmlineExecutionModel.CORE_WASM,
            WasmlineInvocationProtocol.WASMLINE_SERVICE,
        ),
        aotCompatibilityProfiles = listOf(
            profile(CRANELIFT_PROFILE_ID, WasmlineEngineKind.CRANELIFT),
            profile(OTHER_CRANELIFT_PROFILE_ID, WasmlineEngineKind.CRANELIFT),
            profile(PULLEY_PROFILE_ID, WasmlineEngineKind.PULLEY),
            profile(SECOND_PULLEY_PROFILE_ID, WasmlineEngineKind.PULLEY),
        ),
        artifactTargets = targets,
    )

    private fun profile(id: String, backend: WasmlineEngineKind) = WasmlineAotCompatibilityProfile(
        id = id,
        artifactBackend = backend,
        wasmtimeVersion = "12.3.4",
        wasmtimeDistributionVersion = "12.3.4.1",
        compileProfileSchemaVersion = 1,
    )

    private fun rawTarget() = WasmlineArtifactTarget(
        format = WasmlineArtifactFormat.RAW_WASM,
        architecture = "wasm32",
        pointerWidth = 32,
        variants = listOf(WasmlineArtifactVariant(sha256 = RAW_DIGEST, sizeBytes = 3)),
    )

    private fun cwasmTarget(profileId: String) = WasmlineArtifactTarget(
        format = WasmlineArtifactFormat.CWASM,
        operatingSystem = "linux",
        architecture = "x86_64",
        pointerWidth = 64,
        cpuFeatureProfile = "baseline-v1",
        variants = listOf(variant(profileId, CWASM_DIGEST)),
    )

    private fun pulleyTarget(profileId: String) = WasmlineArtifactTarget(
        format = WasmlineArtifactFormat.PWASM,
        architecture = "pulley64",
        pointerWidth = 64,
        variants = listOf(variant(profileId, PULLEY_DIGEST)),
    )

    private fun variant(profileId: String, digest: String) = WasmlineArtifactVariant(listOf(profileId), digest, 3)

    private fun nativeHost(
        formats: Set<WasmlineArtifactFormat>,
        profiles: Map<WasmlineEngineKind, Set<String>>,
    ): WasmlineHostArtifactTarget {
        val runtime = WasmlineNativeRuntimeInfo(
            backend = WasmlineEngineKind.CRANELIFT,
            supportedArtifactFormats = formats,
            wasmtimeVersion = "12.3.4",
            aotCompatibilityProfileIdsByBackend = profiles,
            nativeBridgeAbiVersion = 1,
            wasmlineReleaseVersion = "1.0.0",
            operatingSystem = "linux",
            architecture = "x86_64",
            pointerWidth = 64,
            supportedCpuFeatureProfiles = setOf("baseline-v1"),
        )
        return WasmlineHostArtifactTarget(
            operatingSystem = "linux",
            architecture = "x86_64",
            pointerWidth = 64,
            supportedArtifactFormats = formats,
            nativeRuntimeInfo = runtime,
        )
    }

    /**
     * Defines immutable digests and compatibility profile IDs used by selector fixtures.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    private companion object {
        const val RAW_DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val CWASM_DIGEST = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val PULLEY_DIGEST = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val SECOND_PULLEY_DIGEST = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        const val CRANELIFT_PROFILE_ID = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
        const val OTHER_CRANELIFT_PROFILE_ID = "sha256:2222222222222222222222222222222222222222222222222222222222222222"
        const val PULLEY_PROFILE_ID = "sha256:3333333333333333333333333333333333333333333333333333333333333333"
        const val SECOND_PULLEY_PROFILE_ID = "sha256:4444444444444444444444444444444444444444444444444444444444444444"
    }
}
