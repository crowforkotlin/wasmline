@file:Suppress("unused", "SpellCheckingInspection")
@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline.loader.model

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
 * 2026-04-08
 * @author crowforkotlin
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
 * 2026-04-08
 * @author crowforkotlin
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
 * 2026-04-08
 * @author crowforkotlin
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
)

/**
 * Supported runtime artifact kinds for the current package pipeline.
 *
 * 2026-04-08
 * @author crowforkotlin
 */
@Serializable
enum class WasmlineArtifactType {
    WASM,
    CWASM,
    PWASM,
}
