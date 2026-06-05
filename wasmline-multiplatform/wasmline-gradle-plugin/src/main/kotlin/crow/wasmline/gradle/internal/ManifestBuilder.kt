@file:Suppress("SpellCheckingInspection", "INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline.gradle.internal

import crow.wasmline.loader.internal.crypto.Ed25519
import crow.wasmline.loader.model.SignedManifestEnvelope
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineManifest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import org.gradle.api.logging.Logger
import java.io.File

/**
 * Builds and signs a `manifest.wlm` file from a list of compiled artifacts.
 *
 * 2026/6/5
 * @author crowforkotlin
 */
internal object ManifestBuilder {

    const val DEFAULT_MANIFEST_NAME = "manifest.wlm"

    private val debugJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * Build a signed `manifest.wlm` file and write it (along with a debug
     * JSON copy) into [outputDir].
     *
     * @return the generated `manifest.wlm` file
     */
    fun buildAndSign(
        artifacts: List<WasmlineArtifact>,
        pluginId: String,
        version: String,
        versionCode: Long,
        minSdkVersion: String,
        displayName: String?,
        author: String?,
        description: String?,
        iconUrl: String?,
        homePageUrl: String?,
        metadata: Map<String, String>,
        signingKeyHex: String,
        outputDir: File,
        logger: Logger,
    ): File {
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

        // Resolve key: if it looks like a file path, read it; otherwise treat as hex.
        val privateKeyHex = resolveKey(signingKeyHex)
        val privateKey = privateKeyHex.decodeHex()

        val manifestBytes = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), manifest)
        val signature = Ed25519.sign(manifestBytes.toByteString(), privateKey)
        logger.lifecycle("Manifest signed with Ed25519")

        val envelope = SignedManifestEnvelope(
            signature = signature.toByteArray(),
            manifest = manifest,
            algorithm = "Ed25519",
        )

        val envelopeBytes = ProtoBuf.encodeToByteArray(SignedManifestEnvelope.serializer(), envelope)
        val wlmFile = File(outputDir, DEFAULT_MANIFEST_NAME)
        wlmFile.writeBytes(envelopeBytes)
        logger.lifecycle("Manifest written to: ${wlmFile.absolutePath} (${envelopeBytes.size} bytes)")

        // Debug JSON copy
        val debugDir = File(outputDir, "debug").apply { mkdirs() }
        val jsonFile = File(debugDir, "manifest.json")
        jsonFile.writeText(debugJson.encodeToString(SignedManifestEnvelope.serializer(), envelope))
        logger.lifecycle("Debug JSON written to: ${jsonFile.absolutePath}")

        return wlmFile
    }

    /**
     * Resolve the signing key parameter. If it points to an existing file,
     * the file content is read and trimmed. Otherwise the value is treated
     * as a hex string.
     */
    private fun resolveKey(key: String): String {
        val trimmed = key.trim()
        val file = File(trimmed)
        if (file.isFile) return file.readText().trim()

        val looksLikePath = trimmed.contains('/') || trimmed.contains('\\') || trimmed.endsWith(".key")
        require(!looksLikePath) { "Key file not found: $trimmed" }

        return trimmed
    }
}
