@file:Suppress("unused", "SpellCheckingInspection")
@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline.loader.model

import crow.wasmline.RawAbiMetadata
import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineEngineKind
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Contains a signed, versioned Wasmline manifest payload.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 *
 * @property signature Detached Ed25519 signature over the versioned raw payload.
 * @property algorithm Signature algorithm identifier.
 * @property publicKeyId Optional trusted-key identifier.
 * @property formatVersion Envelope format version.
 * @property payload Exact serialized [WasmlineManifest] bytes covered by [signature].
 */
@Serializable
data class SignedManifestEnvelope(
    @property:ProtoNumber(1) val signature: ByteArray = byteArrayOf(),
    @property:ProtoNumber(2) val algorithm: String = "Ed25519",
    @property:ProtoNumber(3) val publicKeyId: String? = null,
    @property:ProtoNumber(5) val formatVersion: Int = 0,
    @property:ProtoNumber(6) val payload: ByteArray = byteArrayOf(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignedManifestEnvelope) return false
        return signature.contentEquals(other.signature) &&
            algorithm == other.algorithm &&
            publicKeyId == other.publicKeyId &&
            formatVersion == other.formatVersion &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = signature.contentHashCode()
        result = 31 * result + algorithm.hashCode()
        result = 31 * result + (publicKeyId?.hashCode() ?: 0)
        result = 31 * result + formatVersion
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Describes one logical Wasmline package and all published artifact targets.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 *
 * @property pluginId Stable package identifier.
 * @property version Human-readable package version.
 * @property versionCode Monotonic package version code.
 * @property minSdkVersion Minimum host SDK version accepted by the package.
 * @property displayName Optional display name.
 * @property author Optional package author.
 * @property description Optional package description.
 * @property iconUrl Optional package icon URL.
 * @property homePageUrl Optional package home-page URL.
 * @property buildTimestamp Reproducible build timestamp in milliseconds.
 * @property metadata Additional package metadata.
 * @property runtimeContract Logical execution and invocation contract.
 * @property aotCompatibilityProfiles Immutable AOT compatibility identities.
 * @property artifactTargets Fixed physical targets with profile variants.
 */
@Serializable
data class WasmlineManifest(
    @property:ProtoNumber(1) val pluginId: String,
    @property:ProtoNumber(2) val version: String,
    @property:ProtoNumber(3) val versionCode: Long,
    @property:ProtoNumber(4) val minSdkVersion: String,
    @property:ProtoNumber(5) val displayName: String? = null,
    @property:ProtoNumber(6) val author: String? = null,
    @property:ProtoNumber(7) val description: String? = null,
    @property:ProtoNumber(8) val iconUrl: String? = null,
    @property:ProtoNumber(9) val homePageUrl: String? = null,
    @property:ProtoNumber(10) val buildTimestamp: Long,
    @property:ProtoNumber(11) val metadata: Map<String, String> = emptyMap(),
    @property:ProtoNumber(13) val runtimeContract: WasmlineRuntimeContract,
    @property:ProtoNumber(14) val aotCompatibilityProfiles: List<WasmlineAotCompatibilityProfile> = emptyList(),
    @property:ProtoNumber(15) val artifactTargets: List<WasmlineArtifactTarget>,
)

/**
 * Defines the shared execution and invocation contract for every artifact target.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 *
 * @property executionModel WebAssembly execution model.
 * @property invocationProtocol Host invocation protocol.
 * @property exportName Optional selected export name.
 * @property contractMetadata Additional invocation-contract metadata.
 * @property rawAbi Optional versioned scalar Core Wasm ABI metadata.
 */
@Serializable
data class WasmlineRuntimeContract(
    @property:ProtoNumber(1) val executionModel: WasmlineExecutionModel,
    @property:ProtoNumber(2) val invocationProtocol: WasmlineInvocationProtocol,
    @property:ProtoNumber(3) val exportName: String? = null,
    @property:ProtoNumber(4) val contractMetadata: Map<String, String> = emptyMap(),
    @property:ProtoNumber(5) val rawAbi: RawAbiMetadata? = null,
)

/**
 * Identifies one backend-specific serialized Wasmtime artifact contract.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 *
 * @property id Canonical SHA-256 compatibility identifier.
 * @property artifactBackend Backend that creates and loads artifacts for this profile.
 * @property wasmtimeVersion Upstream Wasmtime semantic version.
 * @property wasmtimeDistributionVersion Downstream immutable distribution version.
 * @property compileProfileSchemaVersion Wasmline compile-profile schema version.
 */
@Serializable
data class WasmlineAotCompatibilityProfile(
    @property:ProtoNumber(1) val id: String,
    @property:ProtoNumber(2) val artifactBackend: WasmlineEngineKind,
    @property:ProtoNumber(3) val wasmtimeVersion: String,
    @property:ProtoNumber(4) val wasmtimeDistributionVersion: String,
    @property:ProtoNumber(5) val compileProfileSchemaVersion: Int,
)

/**
 * Describes one fixed physical artifact target and its compatibility variants.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 *
 * @property format Physical artifact format.
 * @property operatingSystem Canonical target operating system when platform-specific.
 * @property architecture Canonical target architecture.
 * @property pointerWidth Target pointer width in bits.
 * @property cpuFeatureProfile Deterministic CPU feature policy for CWASM.
 * @property variants Content-addressed variants indexed by AOT profile.
 */
@Serializable
data class WasmlineArtifactTarget(
    @property:ProtoNumber(1) val format: WasmlineArtifactFormat,
    @property:ProtoNumber(2) val operatingSystem: String? = null,
    @property:ProtoNumber(3) val architecture: String? = null,
    @property:ProtoNumber(4) val pointerWidth: Int? = null,
    @property:ProtoNumber(5) val cpuFeatureProfile: String? = null,
    @property:ProtoNumber(6) val variants: List<WasmlineArtifactVariant>,
)

/**
 * Maps one or more compatible AOT profiles to a content-addressed artifact.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 *
 * @property aotCompatibilityProfileIds Backend-specific AOT profile identifiers.
 * @property sha256 Lowercase SHA-256 artifact digest.
 * @property sizeBytes Exact artifact size in bytes.
 */
@Serializable
data class WasmlineArtifactVariant(
    @property:ProtoNumber(1) val aotCompatibilityProfileIds: List<String> = emptyList(),
    @property:ProtoNumber(2) val sha256: String,
    @property:ProtoNumber(3) val sizeBytes: Long,
)
