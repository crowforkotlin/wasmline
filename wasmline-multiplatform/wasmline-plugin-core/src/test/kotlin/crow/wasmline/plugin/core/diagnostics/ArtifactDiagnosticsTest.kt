package crow.wasmline.plugin.core.diagnostics

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineEngineKind
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.WasmlineArtifactTarget
import crow.wasmline.loader.model.WasmlineArtifactVariant
import crow.wasmline.loader.model.WasmlineRuntimeContract
import crow.wasmline.plugin.core.aot.AotCompatibilityProfileSpec
import crow.wasmline.plugin.core.aot.WasmlineAotBuildRecord
import crow.wasmline.plugin.core.aot.WasmlineAotCompileOptions
import crow.wasmline.plugin.core.aot.WasmlineCompiledArtifact
import kotlin.test.Test
import kotlin.test.assertEquals

class ArtifactDiagnosticsTest {
    @Test
    fun describesAotArtifactFromItsExactProfile() {
        val output = compiledArtifact()
        val diagnostic = WasmlineArtifactDiagnostics.describe(output, buildRecord(output))

        assertEquals(WasmlineArtifactFormat.CWASM, diagnostic.format)
        assertEquals(WasmlineExecutionModel.CORE_WASM, diagnostic.executionModel)
        assertEquals(WasmlineEngineKind.CRANELIFT, diagnostic.artifactBackend)
        assertEquals("x86_64-unknown-linux-gnu", diagnostic.target)
        assertEquals("12.3.4", diagnostic.wasmtimeVersion)
        assertEquals(PROFILE_ID, diagnostic.aotCompatibilityProfileId)
    }

    @Test
    fun rendersContentPathAndFullCompatibilityIdentity() {
        val output = compiledArtifact()

        assertEquals(
            "artifact=${output.contentRelativePath} format=CWASM executionModel=CORE_WASM " +
                "backend=CRANELIFT target=x86_64-unknown-linux-gnu wasmtime=12.3.4 profile=$PROFILE_ID",
            WasmlineArtifactDiagnostics.format(output, buildRecord(output)),
        )
    }

    private fun compiledArtifact(): WasmlineCompiledArtifact = WasmlineCompiledArtifact(
        requestedTarget = "x86_64-linux",
        normalizedTarget = "x86_64-unknown-linux-gnu",
        format = WasmlineArtifactFormat.CWASM,
        artifactBackend = WasmlineEngineKind.CRANELIFT,
        aotCompatibilityProfileId = PROFILE_ID,
        operatingSystem = "linux",
        architecture = "x86_64",
        pointerWidth = 64,
        cpuFeatureProfile = "baseline-v1",
        sha256 = DIGEST,
        sizeBytes = 3,
        contentRelativePath = "artifacts/sha256/aa/$DIGEST.cwasm",
    )

    private fun buildRecord(output: WasmlineCompiledArtifact): WasmlineAotBuildRecord {
        val profile = AotCompatibilityProfileSpec(
            id = PROFILE_ID,
            artifactBackend = WasmlineEngineKind.CRANELIFT,
            wasmtimeVersion = "12.3.4",
            wasmtimeDistributionVersion = "12.3.4.1",
            wasmtimeSourceRevision = "revision",
            serializedArtifactFormatIdentity = "format",
            compileProfileSchemaVersion = 1,
            engineConfigurationProfile = WasmlineAotCompileOptions.FROZEN_DESCRIPTOR,
            introducedInWasmlineVersion = "1.0.0",
        )
        val target = WasmlineArtifactTarget(
            format = WasmlineArtifactFormat.CWASM,
            operatingSystem = "linux",
            architecture = "x86_64",
            pointerWidth = 64,
            cpuFeatureProfile = "baseline-v1",
            variants = listOf(WasmlineArtifactVariant(listOf(PROFILE_ID), DIGEST, 3)),
        )
        return WasmlineAotBuildRecord(
            inputFile = "plugin.wasm",
            inputSha256 = "b".repeat(64),
            runtimeContract = WasmlineRuntimeContract(
                WasmlineExecutionModel.CORE_WASM,
                WasmlineInvocationProtocol.WASMLINE_SERVICE,
            ),
            resolvedProfiles = listOf(profile),
            requestedTargets = listOf("x86_64-linux"),
            compiledOutputs = listOf(output),
            compilerProvenance = emptyList(),
            compileOptions = WasmlineAotCompileOptions(),
            artifactTargets = listOf(target),
        )
    }

    /**
     * Defines immutable diagnostic fixture identities.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    private companion object {
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val PROFILE_ID = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
