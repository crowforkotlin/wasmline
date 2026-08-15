@file:OptIn(
    crow.wasmline.loader.tooling.WasmlineLoaderToolingApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package crow.wasmline.plugin.core.manifest

import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.SignedManifestEnvelope
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineManifest
import crow.wasmline.loader.tooling.WasmlineSigningTooling
import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okio.ByteString.Companion.decodeHex
import java.io.File

/**
 * Creates signed Wasmline manifests.
 *
 * Date: 2026-07-31
 * Author: crowforkotlin
 */

@InternalWasmlineToolingApi
class ManifestSigner {

    companion object {
        const val DEFAULT_MANIFEST_NAME = "manifest.wlm"

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
        executionModel: WasmlineExecutionModel = WasmlineExecutionModel.CORE_WASM,
        invocationProtocol: WasmlineInvocationProtocol = WasmlineInvocationProtocol.WASMLINE_SERVICE,
        exportName: String? = null,
        contractMetadata: Map<String, String> = emptyMap(),
        logger: (String) -> Unit = {},
    ): File {
        outputDir.mkdirs()
        val descriptorError = WasmlineArtifactDescriptor(
            path = "manifest",
            executionModel = executionModel,
            invocationProtocol = invocationProtocol,
            exportName = exportName,
            contractMetadata = contractMetadata,
        ).validationError()
        require(descriptorError == null) { "Invalid artifact invocation descriptor: $descriptorError" }
        artifacts.filter { it.executionModel == WasmlineExecutionModel.COMPONENT_MODEL }.forEach { artifact ->
            require(executionModel == artifact.executionModel) {
                "Component artifact '${artifact.url}' execution model cannot be overwritten during manifest signing."
            }
            require(invocationProtocol == artifact.invocationProtocol) {
                "Component artifact '${artifact.url}' invocation protocol cannot be overwritten during manifest signing: " +
                    "artifact=${artifact.invocationProtocol}, requested=$invocationProtocol."
            }
            require(exportName == artifact.exportName) {
                "Component artifact '${artifact.url}' export metadata cannot be overwritten during manifest signing."
            }
            val conflictingMetadata = contractMetadata.keys.filter { key ->
                key in artifact.contractMetadata && artifact.contractMetadata[key] != contractMetadata[key]
            }
            require(conflictingMetadata.isEmpty()) {
                "Component artifact '${artifact.url}' has conflicting contract metadata: " +
                    conflictingMetadata.sorted().joinToString()
            }
        }
        val describedArtifacts = artifacts.map { artifact ->
            artifact.copy(
                executionModel = executionModel,
                invocationProtocol = invocationProtocol,
                exportName = exportName,
                contractMetadata = artifact.contractMetadata + contractMetadata,
            )
        }
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
            artifacts = describedArtifacts,
        )
        val privateKey = resolveKey(signingKey).decodeHex()
        val manifestBytes = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), manifest)
        val signature = WasmlineSigningTooling.signEd25519(manifestBytes, privateKey.toByteArray())
        val envelope = SignedManifestEnvelope(
            signature = signature,
            algorithm = "Ed25519",
            manifest = manifest,
        )
        val manifestFile = File(outputDir, DEFAULT_MANIFEST_NAME)
        manifestFile.writeBytes(ProtoBuf.encodeToByteArray(SignedManifestEnvelope.serializer(), envelope))

        val debugDir = File(outputDir, "debug").apply { mkdirs() }
        File(debugDir, "manifest.json").writeText(debugJson.encodeToString(envelope))
        logger("Manifest signed with Ed25519")
        logger("Manifest written to: ${manifestFile.absolutePath} (${manifestFile.length()} bytes)")
        return manifestFile
    }
}
