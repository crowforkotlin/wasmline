@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package crow.wasmline.plugin.core.manifest

import crow.wasmline.loader.internal.crypto.Ed25519
import crow.wasmline.loader.model.SignedManifestEnvelope
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineManifest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import java.io.File

/**
 * Creates signed Wasmline manifests.
 *
 * 2026/7/31
 * @author crowforkotlin
 */
class ManifestSigner {

    companion object {
        const val defaultManifestName = "manifest.wlm"

        private val debugJson = Json {
            prettyPrint = true
            encodeDefaults = true
        }

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

    /** Creates a signed manifest file and a readable debug file. */
    fun createSignedManifest(
        artifacts: List<WasmlineArtifact>,
        pluginId: String,
        version: String,
        versionCode: Long,
        minSdkVersion: String,
        signingKey: String,
        outputDir: File,
        displayName: String? = null,
        author: String? = null,
        description: String? = null,
        iconUrl: String? = null,
        homePageUrl: String? = null,
        metadata: Map<String, String> = emptyMap(),
        logger: (String) -> Unit = {},
    ): File {
        outputDir.mkdirs()
        val manifest = WasmlineManifest(
            pluginId = pluginId,
            version = version,
            versionCode = versionCode,
            minSdkVersion = minSdkVersion,
            displayName = displayName,
            author = author,
            description = description,
            iconUrl = iconUrl,
            homePageUrl = homePageUrl,
            buildTimestamp = System.currentTimeMillis(),
            metadata = metadata,
            artifacts = artifacts,
        )
        val privateKey = resolveKey(signingKey).decodeHex()
        val manifestBytes = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), manifest)
        val signature = Ed25519.sign(manifestBytes.toByteString(), privateKey)
        val envelope = SignedManifestEnvelope(
            signature = signature.toByteArray(),
            algorithm = "Ed25519",
            manifest = manifest,
        )
        val manifestFile = File(outputDir, defaultManifestName)
        manifestFile.writeBytes(ProtoBuf.encodeToByteArray(SignedManifestEnvelope.serializer(), envelope))

        val debugDir = File(outputDir, "debug").apply { mkdirs() }
        File(debugDir, "manifest.json").writeText(debugJson.encodeToString(envelope))
        logger("Manifest signed with Ed25519")
        logger("Manifest written to: ${manifestFile.absolutePath} (${manifestFile.length()} bytes)")
        return manifestFile
    }
}
