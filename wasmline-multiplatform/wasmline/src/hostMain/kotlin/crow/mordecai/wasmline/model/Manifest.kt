@file:Suppress("unused", "SpellCheckingInspection")
@file:OptIn(ExperimentalSerializationApi::class)

package crow.mordecai.wasmline.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Structure of the deployment envelope in binary format.
 * Contains the encrypted signature and the serialized Manifest data.
 *
 * @property manifest The serialized byte stream of WasmlineManifest (Protobuf or JSON bytes).
 * @property signature The Ed25519 signature of the payload.
 * @property algorithm The signature algorithm used, enabling future rotation. Defaults to "Ed25519".
 * @property publicKeyId Optional identifier specifying which public key to use for verification (multi-key scenarios).
 * @constructor Create empty Signed manifest envelope
 *
 * 2026-01-22
 * @author crowforkotlin
 */
@Serializable
data class SignedManifestEnvelope(
    @property:ProtoNumber(1) val signature: ByteArray,
    @property:ProtoNumber(2) val algorithm: String = "Ed25519",
    @property:ProtoNumber(3) val publicKeyId: String? = null,
    @property:ProtoNumber(4) val manifest: WasmlineManifest
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
 * The Core Manifest describing the plugin.
 * Refined for "App Store" like display and multi-platform dispatch.
 *
 * @property pluginId Unique identifier (e.g., "com.mordecai.filter").
 * @property version Semantic version (e.g., "1.2.0").
 * @property versionCode Integer version for updates.
 * @property minSdkVersion API compatibility check.
 * @property displayName display name (e.g., "Super Image Filter").
 * @property author Developer name.
 * @property description Short summary.
 * @property iconUrl Relative path or absolute URL to the icon image (PNG/SVG).
 * @property homePageUrl Website or Git repo URL.
 * @property buildTimestamp Absolute build time (Epoch millis).
 * @property metadata Extension map for custom fields.
 * @property artifacts The list of binaries.
 * @constructor Create empty Wasmline manifest
 *
 * 2026-01-22
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
    @property:ProtoNumber(12) val artifacts: List<WasmlineArtifact>
)

/**
 * Represents a specific binary artifact within the manifest.
 *
 * @property type The type of the artifact (CWASM, PWASM, WASM).
 * @property url Relative path (./lib.cwasm) or CDN address.
 * @property sha256 File integrity hash (Hex string).
 *  * @property targetCompilerVersion ABI compatibility check.
 * @property targetCpu Target CPU architecture (e.g., "arm64", "x64"). Specific to AOT.
 * @property targetOs Target Operating System (e.g., "android", "linux", "ios").
 * @property is64Bit Flag for 64-bit architecture, primarily used for Pulley/Interpreter distinction.
 * @constructor Create empty Artifact
 *
 * 2026-01-22
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
    @property:ProtoNumber(7) val is64Bit: Boolean = true
)

/**
 * Enumeration of supported artifact types.
 *
 * CWASM: AOT Compiled (CPU Bound, Fast).
 * PWASM: Pulley Bytecode (Portable, Interpreter).
 * WASM: Standard Wasm (Web / JIT).
 *
 * 2026-01-22
 * @author crowforkotlin
 */
@Serializable
enum class WasmlineArtifactType {
    CWASM,
    PWASM,
    WASM
}