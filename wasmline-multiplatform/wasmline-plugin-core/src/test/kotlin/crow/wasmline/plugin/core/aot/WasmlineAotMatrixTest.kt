package crow.wasmline.plugin.core.aot

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineEngineKind
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.WasmlineRuntimeContract
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies multi-version matrix planning and content variant aggregation.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
class WasmlineAotMatrixTest {
    @Test
    fun plansCurrentProfilesWithoutCrossingArtifactBackends() {
        val targets = WasmlineArtifactTargetFactory.create(
            listOf("x86_64-linux", "aarch64-linux", "pulley32", "pulley64"),
        )
        val profiles = AotCompatibilityCatalog.profiles()

        val units = planWasmlineAotBuildUnits(targets, profiles)

        assertEquals(4, units.size)
        assertTrue(units.all { (target, profile) -> target.artifactBackend == profile.artifactBackend })
        assertEquals(2, units.count { it.first.artifactBackend == WasmlineEngineKind.CRANELIFT })
        assertEquals(2, units.count { it.first.artifactBackend == WasmlineEngineKind.PULLEY })
    }

    @Test
    fun mergesEqualProfileOutputsAndKeepsRawWasmProfileIndependent() {
        val pulleyProfiles = AotCompatibilityCatalog.profiles()
            .filter { it.artifactBackend == WasmlineEngineKind.PULLEY }
        val digest = "a".repeat(64)
        val outputs = pulleyProfiles.map { profile ->
            compiledPulley(profile.id, digest)
        } + WasmlineCompiledArtifact(
            requestedTarget = "wasm32",
            normalizedTarget = "wasm32",
            format = WasmlineArtifactFormat.RAW_WASM,
            architecture = "wasm32",
            pointerWidth = 32,
            sha256 = "b".repeat(64),
            sizeBytes = 4,
            contentRelativePath = "artifacts/sha256/bb/${"b".repeat(64)}.wasm",
        )

        val targets = aggregateWasmlineArtifactTargets(outputs)
        val pulley = targets.single { it.format == WasmlineArtifactFormat.PWASM }
        val raw = targets.single { it.format == WasmlineArtifactFormat.RAW_WASM }

        assertEquals(1, pulley.variants.size)
        assertEquals(pulleyProfiles.map { it.id }.sorted(), pulley.variants.single().aotCompatibilityProfileIds)
        assertEquals(emptyList(), raw.variants.single().aotCompatibilityProfileIds)
    }

    @Test
    fun componentRequestCannotPublishRawWasm() = withMatrixDirectory { root ->
        val input = File(root, "plugin.component.wasm").apply { writeBytes(byteArrayOf(1)) }

        assertFailsWith<IllegalArgumentException> {
            WasmlineAotBuildRequest(
                inputFile = input,
                packageDirectory = File(root, "package"),
                workingDirectory = File(root, "working"),
                runtimeContract = WasmlineRuntimeContract(
                    WasmlineExecutionModel.COMPONENT_MODEL,
                    WasmlineInvocationProtocol.COMPONENT_EXPORT,
                ),
                targets = listOf("pulley64"),
                resolvedProfileIds = listOf("sha256:${"a".repeat(64)}"),
                aotCompatibilitySelector = "current",
                selectedAotGenerations = listOf(1),
                publishRawWasm = true,
            )
        }
    }

    private fun compiledPulley(profileId: String, digest: String): WasmlineCompiledArtifact = WasmlineCompiledArtifact(
        requestedTarget = "pulley64",
        normalizedTarget = "pulley64",
        format = WasmlineArtifactFormat.PWASM,
        artifactBackend = WasmlineEngineKind.PULLEY,
        aotCompatibilityProfileId = profileId,
        architecture = "pulley64",
        pointerWidth = 64,
        sha256 = digest,
        sizeBytes = 3,
        contentRelativePath = "artifacts/sha256/${digest.take(2)}/$digest.pwasm",
    )
}

private inline fun withMatrixDirectory(block: (File) -> Unit) {
    val directory = createTempDirectory("wasmline-aot-matrix-test").toFile()
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}
