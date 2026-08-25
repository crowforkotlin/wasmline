@file:Suppress("unused", "SpellCheckingInspection")
@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline.loader.model

import crow.wasmline.RawAbiMetadata
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Signed manifest envelope for a Wasmline package.
 *
 * The envelope contains the manifest payload together with its detached
 * signature information so the loader layer can verify integrity before it
 * resolves a concrete runtime artifact.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 *
 * @property signature Detached signature bytes for [manifest].
 * @property algorithm Signature algorithm identifier.
 * @property publicKeyId Optional trusted-key identifier.
 * @property manifest Signed package manifest payload.
 */
@Serializable
data class SignedManifestEnvelope(
    @property:ProtoNumber(1) val signature: ByteArray,
    @property:ProtoNumber(2) val algorithm: String = "Ed25519",
    @property:ProtoNumber(3) val publicKeyId: String? = null,
    @property:ProtoNumber(4) val manifest: WasmlineManifest,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as SignedManifestEnvelope
        if (!signature.contentEquals(other.signature)) return false
        if (manifest != other.manifest) return false
        if (algorithm != other.algorithm) return false
        if (publicKeyId != other.publicKeyId) return false
        return true
    }

    override fun hashCode(): Int {
        var result = signature.contentHashCode()
        result = 31 * result + manifest.hashCode()
        result = 31 * result + algorithm.hashCode()
        result = 31 * result + (publicKeyId?.hashCode() ?: 0)
        return result
    }
}

/**
 * Package manifest model owned by the loader layer.
 *
 * Date: 2026-08-25
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
 * @property buildTimestamp Build timestamp in milliseconds.
 * @property metadata Additional package metadata.
 * @property artifacts Published runtime artifacts.
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
    @property:ProtoNumber(12) val artifacts: List<WasmlineArtifact>,
)

/**
 * Describes one compiled runtime artifact published by a Wasmline package.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 *
 * @property type Physical artifact kind.
 * @property url Artifact URL or package-relative path.
 * @property sha256 Expected SHA-256 digest.
 * @property targetCpu Native CPU target, when applicable.
 * @property targetOs Native operating-system target, when applicable.
 * @property targetCompilerVersion Compiler/runtime compatibility marker.
 * @property is64Bit Native artifact bitness marker.
 * @property executionModel Runtime execution model.
 * @property invocationProtocol Host invocation protocol.
 * @property exportName Optional selected export name.
 * @property contractMetadata Additional invocation-contract metadata.
 * @property rawAbi Versioned scalar Core Wasm ABI metadata for RAW_EXPORT.
 */
@Serializable
data class WasmlineArtifact(
    @property:ProtoNumber(1) val type: WasmlineArtifactType,
    @property:ProtoNumber(2) val url: String,
    @property:ProtoNumber(3) val sha256: String,
    @property:ProtoNumber(4) val targetCpu: String? = null,
    @property:ProtoNumber(5) val targetOs: String? = null,
    @property:ProtoNumber(6) val targetCompilerVersion: String? = null,
    @property:ProtoNumber(7) val is64Bit: Boolean = true,
    @property:ProtoNumber(8) val executionModel: WasmlineExecutionModel = WasmlineExecutionModel.CORE_WASM,
    @property:ProtoNumber(9) val invocationProtocol: WasmlineInvocationProtocol = WasmlineInvocationProtocol.WASMLINE_SERVICE,
    @property:ProtoNumber(10) val exportName: String? = null,
    @property:ProtoNumber(11) val contractMetadata: Map<String, String> = emptyMap(),
    @property:ProtoNumber(12) val rawAbi: RawAbiMetadata? = null,
)

/**
 * Supported runtime artifact kinds for the current package pipeline.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
@Serializable
enum class WasmlineArtifactType {
    WASM,
    CWASM,
    PWASM,
    COMPONENT_WASM,
}
