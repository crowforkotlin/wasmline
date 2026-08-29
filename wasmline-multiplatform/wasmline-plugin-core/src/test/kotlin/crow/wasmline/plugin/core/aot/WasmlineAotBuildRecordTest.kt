package crow.wasmline.plugin.core.aot

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineEngineKind
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.WasmlineArtifactTarget
import crow.wasmline.loader.model.WasmlineArtifactVariant
import crow.wasmline.loader.model.WasmlineRuntimeContract
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies deterministic unified AOT build records and artifact materialization.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
class WasmlineAotBuildRecordTest {
    @Test
    fun roundTripsAndMaterializesContentObjects() = withRecordDirectory { root ->
        val sourcePackage = File(root, "source")
        val sourceArtifact = File(root, "artifact.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val stored = WasmlineContentAddressedStore(sourcePackage).put(sourceArtifact, WasmlineArtifactFormat.PWASM)
        val record = record(stored)
        val recordFile = File(root, "record/${WasmlineAotBuildRecords.FILE_NAME}")

        WasmlineAotBuildRecords.write(record, recordFile)
        val decoded = WasmlineAotBuildRecords.read(recordFile)
        val destination = File(root, "destination")
        WasmlineAotBuildRecords.materializeArtifacts(decoded, sourcePackage, destination)

        assertEquals(record, decoded)
        assertTrue(File(destination, stored.relativePath).isFile)
    }

    private fun record(stored: StoredWasmlineArtifact): WasmlineAotBuildRecord {
        val profile = AotCompatibilityProfileSpec(
            id = PROFILE_ID,
            artifactBackend = WasmlineEngineKind.PULLEY,
            wasmtimeVersion = "12.3.4",
            wasmtimeDistributionVersion = "12.3.4.1",
            wasmtimeSourceRevision = "revision",
            serializedArtifactFormatIdentity = "format",
            compileProfileSchemaVersion = 1,
            engineConfigurationProfile = WasmlineAotCompileOptions.FROZEN_DESCRIPTOR,
            introducedInWasmlineVersion = "1.0.0",
        )
        val output = WasmlineCompiledArtifact(
            requestedTarget = "pulley64",
            normalizedTarget = "pulley64",
            format = WasmlineArtifactFormat.PWASM,
            artifactBackend = WasmlineEngineKind.PULLEY,
            aotCompatibilityProfileId = PROFILE_ID,
            architecture = "pulley64",
            pointerWidth = 64,
            sha256 = stored.sha256,
            sizeBytes = stored.sizeBytes,
            contentRelativePath = stored.relativePath,
        )
        val target = WasmlineArtifactTarget(
            format = WasmlineArtifactFormat.PWASM,
            architecture = "pulley64",
            pointerWidth = 64,
            variants = listOf(WasmlineArtifactVariant(listOf(PROFILE_ID), stored.sha256, stored.sizeBytes)),
        )
        return WasmlineAotBuildRecord(
            inputFile = "plugin.wasm",
            inputSha256 = "a".repeat(64),
            runtimeContract = WasmlineRuntimeContract(
                WasmlineExecutionModel.CORE_WASM,
                WasmlineInvocationProtocol.WASMLINE_SERVICE,
            ),
            resolvedProfiles = listOf(profile),
            requestedTargets = listOf("pulley64"),
            compiledOutputs = listOf(output),
            compilerProvenance = emptyList(),
            compileOptions = WasmlineAotCompileOptions(),
            artifactTargets = listOf(target),
            aotCompatibilitySelector = "current",
            selectedAotGenerations = listOf(1),
        )
    }

    /**
     * Defines the compatibility profile ID shared by build record fixtures.
     *
     * Date: 2026-08-29
     * Author: crowforkotlin
     */
    private companion object {
        const val PROFILE_ID = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}

private inline fun withRecordDirectory(block: (File) -> Unit) {
    val directory = createTempDirectory("wasmline-aot-record-test").toFile()
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}
