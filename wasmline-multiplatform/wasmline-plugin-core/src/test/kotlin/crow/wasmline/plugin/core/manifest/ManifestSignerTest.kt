package crow.wasmline.plugin.core.manifest

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineEngineKind
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.WasmlineArtifactVariant
import crow.wasmline.loader.model.WasmlineRuntimeContract
import crow.wasmline.plugin.core.aot.AotCompatibilityProfileSpec
import crow.wasmline.plugin.core.aot.WasmlineAotBuildRecord
import crow.wasmline.plugin.core.aot.WasmlineAotCompileOptions
import crow.wasmline.plugin.core.aot.WasmlineAotCompilerProvenance
import crow.wasmline.plugin.core.aot.WasmlineCompiledArtifact
import crow.wasmline.plugin.core.aot.aggregateWasmlineArtifactTargets
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that manifest signing rejects incomplete or inconsistent AOT build records.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
class ManifestSignerTest {
    @Test
    fun rejectsIncompleteProfileTargetMatrixBeforeWritingManifest() = withSigningDirectory { directory ->
        val firstProfile = profile(FIRST_PROFILE_ID, "47.0.3")
        val secondProfile = profile(SECOND_PROFILE_ID, "47.0.4")
        val outputs = listOf(aotOutput(firstProfile.id), rawOutput())
        val record = record(
            profiles = listOf(firstProfile, secondProfile),
            outputs = outputs,
            artifactTargets = aggregateWasmlineArtifactTargets(outputs),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            ManifestSigner().createSignedManifest(signingRequest(record, directory))
        }

        assertTrue(failure.message.orEmpty().contains("complete profile and target matrix"))
        assertFalse(File(directory, ManifestSigner.DEFAULT_MANIFEST_NAME).exists())
    }

    @Test
    fun rejectsArtifactTargetsThatDoNotMatchCompiledOutputs() = withSigningDirectory { directory ->
        val selectedProfile = profile(FIRST_PROFILE_ID, "47.0.3")
        val outputs = listOf(aotOutput(selectedProfile.id), rawOutput())
        val targets = aggregateWasmlineArtifactTargets(outputs).map { target ->
            if (target.format == WasmlineArtifactFormat.PWASM) {
                target.copy(
                    variants = target.variants.map { variant ->
                        WasmlineArtifactVariant(
                            aotCompatibilityProfileIds = variant.aotCompatibilityProfileIds,
                            sha256 = "f".repeat(64),
                            sizeBytes = variant.sizeBytes,
                        )
                    },
                )
            } else {
                target
            }
        }
        val record = record(listOf(selectedProfile), outputs, targets)

        val failure = assertFailsWith<IllegalArgumentException> {
            ManifestSigner().createSignedManifest(signingRequest(record, directory))
        }

        assertTrue(failure.message.orEmpty().contains("artifact targets do not match"))
        assertFalse(File(directory, ManifestSigner.DEFAULT_MANIFEST_NAME).exists())
    }
}

/** Creates one immutable Pulley profile fixture. */
private fun profile(id: String, wasmtimeVersion: String): AotCompatibilityProfileSpec = AotCompatibilityProfileSpec(
    id = id,
    artifactBackend = WasmlineEngineKind.PULLEY,
    wasmtimeVersion = wasmtimeVersion,
    wasmtimeDistributionVersion = "$wasmtimeVersion.1",
    wasmtimeSourceRevision = "revision-$wasmtimeVersion",
    serializedArtifactFormatIdentity = "format-v1",
    compileProfileSchemaVersion = 1,
    engineConfigurationProfile = WasmlineAotCompileOptions.FROZEN_DESCRIPTOR,
    introducedInWasmlineVersion = "1.0.0",
)

/** Creates one Pulley matrix output fixture. */
private fun aotOutput(profileId: String): WasmlineCompiledArtifact = WasmlineCompiledArtifact(
    requestedTarget = "pulley64",
    normalizedTarget = "pulley64",
    format = WasmlineArtifactFormat.PWASM,
    artifactBackend = WasmlineEngineKind.PULLEY,
    aotCompatibilityProfileId = profileId,
    architecture = "pulley64",
    pointerWidth = 64,
    sha256 = AOT_DIGEST,
    sizeBytes = 3,
    contentRelativePath = "artifacts/sha256/${AOT_DIGEST.take(2)}/$AOT_DIGEST.pwasm",
)

/** Creates the single profile-independent Core Web artifact fixture. */
private fun rawOutput(): WasmlineCompiledArtifact = WasmlineCompiledArtifact(
    requestedTarget = "wasm32",
    normalizedTarget = "wasm32",
    format = WasmlineArtifactFormat.RAW_WASM,
    architecture = "wasm32",
    pointerWidth = 32,
    sha256 = RAW_DIGEST,
    sizeBytes = 3,
    contentRelativePath = "artifacts/sha256/${RAW_DIGEST.take(2)}/$RAW_DIGEST.wasm",
)

/** Creates a signable record fixture with supplied matrix state. */
private fun record(
    profiles: List<AotCompatibilityProfileSpec>,
    outputs: List<WasmlineCompiledArtifact>,
    artifactTargets: List<crow.wasmline.loader.model.WasmlineArtifactTarget>,
): WasmlineAotBuildRecord = WasmlineAotBuildRecord(
    inputFile = "plugin.wasm",
    inputSha256 = "c".repeat(64),
    runtimeContract = WasmlineRuntimeContract(
        WasmlineExecutionModel.CORE_WASM,
        WasmlineInvocationProtocol.WASMLINE_SERVICE,
    ),
    resolvedProfiles = profiles,
    requestedTargets = listOf("pulley64"),
    compiledOutputs = outputs,
    compilerProvenance = profiles.map { selected ->
        WasmlineAotCompilerProvenance(
            profileId = selected.id,
            artifactBackend = selected.artifactBackend,
            wasmtimeVersion = selected.wasmtimeVersion,
            wasmtimeDistributionVersion = selected.wasmtimeDistributionVersion,
            buildHost = "x86_64-linux",
            compilerArchiveSha256 = "d".repeat(64),
            compilerExecutableSha256 = "e".repeat(64),
        )
    },
    compileOptions = WasmlineAotCompileOptions(),
    artifactTargets = artifactTargets,
)

/** Creates a signing request whose key is never reached by invalid fixtures. */
private fun signingRequest(record: WasmlineAotBuildRecord, directory: File): WasmlineManifestSigningRequest =
    WasmlineManifestSigningRequest(
        buildRecord = record,
        pluginId = "crow.wasmline.signer-test",
        version = "1.0.0",
        versionCode = 1,
        minSdkVersion = "1.0.0",
        buildTimestamp = 0,
        signingKey = "00",
        outputDirectory = directory,
    )

/** Runs one test with an isolated output directory. */
private inline fun withSigningDirectory(block: (File) -> Unit) {
    val directory = createTempDirectory("wasmline-manifest-signer-test").toFile()
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}

private const val FIRST_PROFILE_ID = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
private const val SECOND_PROFILE_ID = "sha256:2222222222222222222222222222222222222222222222222222222222222222"
private const val AOT_DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val RAW_DIGEST = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
