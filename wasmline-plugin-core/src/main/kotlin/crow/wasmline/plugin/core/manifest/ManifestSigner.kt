@file:OptIn(
    crow.wasmline.loader.tooling.WasmlineLoaderToolingApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package crow.wasmline.plugin.core.manifest

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineEngineKind
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.loader.model.SignedManifestEnvelope
import crow.wasmline.loader.model.WasmlineAotCompatibilityProfile
import crow.wasmline.loader.model.WasmlineManifest
import crow.wasmline.loader.model.WasmlineManifestProtocol
import crow.wasmline.loader.model.WasmlineManifestWireFormat
import crow.wasmline.loader.tooling.WasmlineSigningTooling
import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import crow.wasmline.plugin.core.aot.WasmlineAotBuildRecord
import crow.wasmline.plugin.core.aot.WasmlineAotBuildRecords
import crow.wasmline.plugin.core.aot.WasmlineAotTargetSpec
import crow.wasmline.plugin.core.aot.WasmlineArtifactTargetFactory
import crow.wasmline.plugin.core.aot.WasmlineCompiledArtifact
import crow.wasmline.plugin.core.aot.aggregateWasmlineArtifactTargets
import crow.wasmline.plugin.core.aot.planWasmlineAotBuildUnits
import crow.wasmline.plugin.core.toolchain.FileDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okio.ByteString.Companion.decodeHex
import java.io.File

/**
 * Defines the complete reproducible input for one signed Wasmline manifest.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
data class WasmlineManifestSigningRequest(
    val buildRecord: WasmlineAotBuildRecord,
    val pluginId: String,
    val version: String,
    val versionCode: Long,
    val minSdkVersion: String,
    val buildTimestamp: Long,
    val signingKey: String,
    val outputDirectory: File,
    val displayName: String? = null,
    val author: String? = null,
    val description: String? = null,
    val iconUrl: String? = null,
    val homePageUrl: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val logger: (String) -> Unit = {},
)

/**
 * Provides a human-readable index of every content-addressed build output.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@Serializable
@InternalWasmlineToolingApi
data class WasmlineArtifactIndex(val schemaVersion: Int = 1, val artifacts: List<WasmlineArtifactIndexEntry>)

/**
 * Maps one build matrix output to its compatibility profile and content path.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@Serializable
@InternalWasmlineToolingApi
data class WasmlineArtifactIndexEntry(
    val wasmtimeVersion: String? = null,
    val artifactBackend: WasmlineEngineKind? = null,
    val aotCompatibilityProfileId: String? = null,
    val requestedTarget: String,
    val normalizedTarget: String,
    val format: WasmlineArtifactFormat,
    val sha256: String,
    val sizeBytes: Long,
    val contentRelativePath: String,
)

/**
 * Creates signed Wasmline manifests from a complete unified AOT build record.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
class ManifestSigner {
    /** Creates a signed manifest and deterministic debug records. */
    fun createSignedManifest(request: WasmlineManifestSigningRequest): File {
        validateRequest(request)
        validateBuildRecord(request.buildRecord)
        val outputDirectory = request.outputDirectory.apply {
            check(isDirectory || mkdirs()) { "Unable to create manifest output directory: $absolutePath" }
        }
        verifyContentObjects(request.buildRecord, outputDirectory)

        val manifest = WasmlineManifestProtocol.canonicalize(
            WasmlineManifest(
                pluginId = request.pluginId,
                version = request.version,
                versionCode = request.versionCode,
                minSdkVersion = request.minSdkVersion,
                displayName = request.displayName,
                author = request.author,
                description = request.description,
                iconUrl = request.iconUrl,
                homePageUrl = request.homePageUrl,
                buildTimestamp = request.buildTimestamp,
                metadata = request.metadata,
                runtimeContract = request.buildRecord.runtimeContract,
                aotCompatibilityProfiles = request.buildRecord.resolvedProfiles.map { profile ->
                    WasmlineAotCompatibilityProfile(
                        id = profile.id,
                        artifactBackend = profile.artifactBackend,
                        wasmtimeVersion = profile.wasmtimeVersion,
                        wasmtimeDistributionVersion = profile.wasmtimeDistributionVersion,
                        compileProfileSchemaVersion = profile.compileProfileSchemaVersion,
                    )
                },
                artifactTargets = request.buildRecord.artifactTargets,
            ),
        )
        WasmlineManifestProtocol.validationError(manifest)?.let { cause ->
            error("Cannot sign an invalid Wasmline manifest: $cause")
        }

        val payload = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), manifest)
        val formatVersion = WasmlineManifestWireFormat.CURRENT_FORMAT_VERSION
        val privateKey = resolveKey(request.signingKey).decodeHex().toByteArray()
        val signature = WasmlineSigningTooling.signEd25519(
            WasmlineManifestProtocol.signingMessage(formatVersion, payload),
            privateKey,
        )
        val envelope = SignedManifestEnvelope(
            signature = signature,
            algorithm = WasmlineManifestWireFormat.SIGNATURE_ALGORITHM,
            formatVersion = formatVersion,
            payload = payload,
        )
        val manifestFile = File(outputDirectory, DEFAULT_MANIFEST_NAME)
        manifestFile.writeBytes(ProtoBuf.encodeToByteArray(SignedManifestEnvelope.serializer(), envelope))
        writeDebugRecords(request.buildRecord, manifest, outputDirectory)
        request.logger("Manifest signed with ${WasmlineManifestWireFormat.SIGNATURE_ALGORITHM}")
        request.logger("Manifest written to: ${manifestFile.absolutePath} (${manifestFile.length()} bytes)")
        return manifestFile
    }

    private fun validateRequest(request: WasmlineManifestSigningRequest) {
        require(request.pluginId.isNotBlank()) { "Manifest pluginId must not be blank." }
        require(request.version.isNotBlank()) { "Manifest version must not be blank." }
        require(request.versionCode >= 0) { "Manifest versionCode must be non-negative." }
        require(request.minSdkVersion.isNotBlank()) { "Manifest minSdkVersion must not be blank." }
        require(request.buildTimestamp >= 0) { "Manifest buildTimestamp must be non-negative." }
    }

    private fun validateBuildRecord(record: WasmlineAotBuildRecord) {
        val profilesById = record.resolvedProfiles.associateBy { it.id }
        require(profilesById.size == record.resolvedProfiles.size) {
            "AOT build record contains duplicate compatibility profiles."
        }
        record.resolvedProfiles.forEach { profile ->
            require(profile.engineConfigurationProfile == record.compileOptions.canonicalDescriptor()) {
                "AOT profile '${profile.id}' does not match the recorded compile options."
            }
        }

        val targetSpecs = WasmlineArtifactTargetFactory.create(record.requestedTargets)
        val targetSpecsByName = targetSpecs.associateBy(WasmlineAotTargetSpec::normalizedTarget)
        val expectedUnits = planWasmlineAotBuildUnits(targetSpecs, record.resolvedProfiles)
            .map { (target, profile) -> target.normalizedTarget to profile.id }
            .toSet()
        val actualUnits = mutableSetOf<Pair<String, String>>()
        val aotOutputs = record.compiledOutputs.filter { it.format != WasmlineArtifactFormat.RAW_WASM }
        aotOutputs.forEach { output ->
            val profileId = requireNotNull(output.aotCompatibilityProfileId) {
                "AOT output '${output.normalizedTarget}' does not identify a compatibility profile."
            }
            val profile = profilesById[profileId]
                ?: error("AOT output '${output.normalizedTarget}' references unknown profile '$profileId'.")
            val target = targetSpecsByName[output.normalizedTarget]
                ?: error("AOT output references unrequested target '${output.normalizedTarget}'.")
            validateCompiledTarget(output, target, profile.artifactBackend)
            require(actualUnits.add(output.normalizedTarget to profileId)) {
                "AOT build record contains duplicate matrix unit '${output.normalizedTarget}/$profileId'."
            }
        }
        require(actualUnits == expectedUnits) {
            "AOT build record does not contain the complete profile and target matrix."
        }

        val rawOutputs = record.compiledOutputs.filter { it.format == WasmlineArtifactFormat.RAW_WASM }
        if (record.runtimeContract.executionModel == WasmlineExecutionModel.CORE_WASM) {
            require(rawOutputs.size == 1) { "Core Wasm build records must contain exactly one RAW_WASM output." }
            validateRawWasmOutput(rawOutputs.single())
        } else {
            require(rawOutputs.isEmpty()) { "Component build records must not publish RAW_WASM output." }
        }

        val expectedTargets = aggregateWasmlineArtifactTargets(record.compiledOutputs)
        require(record.artifactTargets == expectedTargets) {
            "AOT build record artifact targets do not match its compiled outputs."
        }

        val provenanceByProfile = record.compilerProvenance.associateBy { it.profileId }
        require(provenanceByProfile.size == record.compilerProvenance.size) {
            "AOT build record contains duplicate compiler provenance."
        }
        require(provenanceByProfile.keys == profilesById.keys) {
            "AOT build record must contain compiler provenance for every resolved profile."
        }
        record.compilerProvenance.forEach { provenance ->
            val profile = profilesById.getValue(provenance.profileId)
            require(
                provenance.artifactBackend == profile.artifactBackend &&
                    provenance.wasmtimeVersion == profile.wasmtimeVersion &&
                    provenance.wasmtimeDistributionVersion == profile.wasmtimeDistributionVersion,
            ) {
                "Compiler provenance does not match AOT profile '${profile.id}'."
            }
            require(SHA256_PATTERN.matches(provenance.compilerArchiveSha256)) {
                "Compiler archive provenance for '${profile.id}' has an invalid SHA-256."
            }
            require(SHA256_PATTERN.matches(provenance.compilerExecutableSha256)) {
                "Compiler executable provenance for '${profile.id}' has an invalid SHA-256."
            }
        }
    }

    private fun validateCompiledTarget(
        output: WasmlineCompiledArtifact,
        target: WasmlineAotTargetSpec,
        profileBackend: WasmlineEngineKind,
    ) {
        require(output.requestedTarget == target.requestedTarget) {
            "AOT output '${output.normalizedTarget}' does not preserve its requested target."
        }
        require(
            output.artifactBackend == target.artifactBackend &&
                profileBackend == target.artifactBackend &&
                output.format == target.format &&
                output.operatingSystem == target.operatingSystem &&
                output.architecture == target.architecture &&
                output.pointerWidth == target.pointerWidth &&
                output.cpuFeatureProfile == target.cpuFeatureProfile,
        ) {
            "AOT output '${output.normalizedTarget}' does not match its normalized target or profile backend."
        }
        validateContentIdentity(output)
    }

    private fun validateRawWasmOutput(output: WasmlineCompiledArtifact) {
        require(
            output.requestedTarget == "wasm32" &&
                output.normalizedTarget == "wasm32" &&
                output.artifactBackend == null &&
                output.aotCompatibilityProfileId == null &&
                output.operatingSystem == null &&
                output.architecture == "wasm32" &&
                output.pointerWidth == 32 &&
                output.cpuFeatureProfile == null,
        ) {
            "RAW_WASM output does not use the canonical profile-independent target."
        }
        validateContentIdentity(output)
    }

    private fun validateContentIdentity(output: WasmlineCompiledArtifact) {
        require(SHA256_PATTERN.matches(output.sha256) && output.sizeBytes > 0) {
            "Compiled output '${output.normalizedTarget}' has an invalid content identity."
        }
        require(
            output.contentRelativePath == WasmlineManifestProtocol.artifactRelativePath(output.sha256, output.format),
        ) {
            "Compiled output '${output.normalizedTarget}' has a non-standard content path."
        }
    }

    private fun verifyContentObjects(record: WasmlineAotBuildRecord, packageDirectory: File) {
        record.compiledOutputs.distinctBy(WasmlineCompiledArtifact::contentRelativePath).forEach { output ->
            val expectedPath = WasmlineManifestProtocol.artifactRelativePath(output.sha256, output.format)
            require(output.contentRelativePath == expectedPath) {
                "AOT build record contains a non-standard content path '${output.contentRelativePath}'."
            }
            val file = File(packageDirectory, expectedPath)
            require(file.isFile && file.length() == output.sizeBytes) {
                "AOT content object is missing or has the wrong size: ${file.absolutePath}"
            }
            require(FileDigest.sha256Hex(file) == output.sha256) {
                "AOT content object failed SHA-256 verification: ${file.absolutePath}"
            }
        }
    }

    private fun writeDebugRecords(record: WasmlineAotBuildRecord, manifest: WasmlineManifest, outputDirectory: File) {
        val debugDirectory = File(outputDirectory, "debug").apply {
            check(isDirectory || mkdirs()) { "Unable to create manifest debug directory: $absolutePath" }
        }
        File(debugDirectory, MANIFEST_JSON_NAME).writeText(DEBUG_JSON.encodeToString(manifest))
        WasmlineAotBuildRecords.write(record, File(debugDirectory, WasmlineAotBuildRecords.FILE_NAME))
        val profilesById = record.resolvedProfiles.associateBy { it.id }
        val entries = record.compiledOutputs.map { output ->
            val profile = output.aotCompatibilityProfileId?.let(profilesById::getValue)
            WasmlineArtifactIndexEntry(
                wasmtimeVersion = profile?.wasmtimeVersion,
                artifactBackend = output.artifactBackend,
                aotCompatibilityProfileId = output.aotCompatibilityProfileId,
                requestedTarget = output.requestedTarget,
                normalizedTarget = output.normalizedTarget,
                format = output.format,
                sha256 = output.sha256,
                sizeBytes = output.sizeBytes,
                contentRelativePath = output.contentRelativePath,
            )
        }
        File(debugDirectory, ARTIFACT_INDEX_JSON_NAME).writeText(
            DEBUG_JSON.encodeToString(WasmlineArtifactIndex(artifacts = entries)),
        )
    }

    /**
     * Defines stable package and debug filenames.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    companion object {
        const val DEFAULT_MANIFEST_NAME: String = "manifest.wlm"
        const val MANIFEST_JSON_NAME: String = "manifest.json"
        const val ARTIFACT_INDEX_JSON_NAME: String = "artifact-index.json"

        private val DEBUG_JSON = Json {
            prettyPrint = true
            encodeDefaults = true
        }

        private val SHA256_PATTERN: Regex = Regex("^[0-9a-f]{64}$")

        /** Reads a key from a file when the supplied value is a file path. */
        fun resolveKey(key: String): String {
            val trimmed = key.trim()
            val file = File(trimmed)
            if (file.isFile) return file.readText().trim()
            require(!trimmed.contains('/') && !trimmed.contains('\\') && !trimmed.endsWith(".key")) {
                "Key file not found: $trimmed"
            }
            return trimmed
        }
    }
}
