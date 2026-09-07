@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline.loader.internal

import crow.wasmline.WasmlineLoadStage
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.loader.VerifiedPackageArtifact
import crow.wasmline.loader.WasmlineLoadRequest
import crow.wasmline.loader.WasmlineSource
import crow.wasmline.loader.WasmlineSourceResolution
import crow.wasmline.loader.model.SignedManifestEnvelope
import crow.wasmline.loader.model.WasmlineManifestProtocol
import crow.wasmline.loader.toDescriptor
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.HashingSource
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * Resolves a signed local package through the shared manifest selector.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
internal object WasmlineLocalPackageResolution {
    /** Resolves one local manifest and its selected content-addressed artifact. */
    fun resolve(
        source: WasmlineSource.LocalManifestPath,
        request: WasmlineLoadRequest,
        host: WasmlineHostArtifactTarget? = null,
    ): WasmlineSourceResolution {
        val manifestPath = source.path
        if (!hostPathExists(manifestPath)) {
            return failure(
                "Local package manifest not found: ${source.path}",
                WasmlineLoadStage.SOURCE_RESOLUTION,
                WasmlineErrorCode.ARTIFACT_NOT_FOUND,
            )
        }

        val maxManifestBytes = request.options.manifestLimits.maxManifestBytes
        if (hostFileSize(manifestPath)?.let { it > maxManifestBytes } == true) {
            return failure(
                "Local package manifest '${source.path}' exceeds the configured manifest byte limit.",
                WasmlineLoadStage.MANIFEST_DECODING,
                WasmlineErrorCode.MANIFEST_INVALID,
            )
        }
        val manifestBytes = readHostFileBytes(manifestPath) ?: return failure(
            "Failed to read local package manifest '${source.path}'.",
            WasmlineLoadStage.MANIFEST_DECODING,
            WasmlineErrorCode.ARTIFACT_IO_FAILED,
        )
        if (manifestBytes.size > maxManifestBytes) {
            return failure(
                "Local package manifest '${source.path}' exceeds the configured manifest byte limit.",
                WasmlineLoadStage.MANIFEST_DECODING,
                WasmlineErrorCode.MANIFEST_INVALID,
            )
        }
        val envelope = try {
            ProtoBuf.decodeFromByteArray(SignedManifestEnvelope.serializer(), manifestBytes)
        } catch (_: Exception) {
            return failure(
                "Failed to parse local package manifest '${source.path}'.",
                WasmlineLoadStage.MANIFEST_DECODING,
                WasmlineErrorCode.MANIFEST_INVALID,
            )
        }
        val manifest = when (
            val verification = WasmlinePackageSignatureVerifier.verify(
                envelope = envelope,
                trustedKeys = request.options.trustedKeys,
                packageLocation = source.path,
                limits = request.options.manifestLimits,
            )
        ) {
            is WasmlineManifestVerification.Verified -> verification.manifest

            is WasmlineManifestVerification.Rejected -> return failure(
                verification.cause,
                verification.stage,
                verification.code,
            )
        }
        val resolvedHost = host ?: currentHostArtifactTarget
        val selected = when (val selection = WasmlineArtifactSelector.select(manifest, resolvedHost)) {
            is WasmlineArtifactSelection.Selected -> selection

            is WasmlineArtifactSelection.Invalid -> return failure(
                selection.cause,
                WasmlineLoadStage.ARTIFACT_SELECTION,
                WasmlineErrorCode.MANIFEST_INVALID,
            )

            WasmlineArtifactSelection.NotCompatible -> return failure(
                "No compatible artifact found in local package '${source.path}' for host " +
                    describe(resolvedHost) + ".",
                WasmlineLoadStage.ARTIFACT_SELECTION,
                WasmlineErrorCode.ARTIFACT_NOT_COMPATIBLE,
            )
        }

        val variant = selected.variant
        if (variant.sizeBytes > request.options.maxArtifactBytes) {
            return failure(
                "Selected artifact '${variant.sha256}' exceeds the configured artifact byte limit.",
                WasmlineLoadStage.ARTIFACT_VALIDATION,
                WasmlineErrorCode.ARTIFACT_INTEGRITY_FAILED,
            )
        }
        val relativePath = WasmlineManifestProtocol.artifactRelativePath(variant.sha256, selected.target.format)
        val artifactPath = resolveHostArtifactPath(manifestPath, relativePath)
        val descriptor = selected.toDescriptor(artifactPath, manifest.runtimeContract)
        descriptor.validationError()?.let { cause ->
            return failure(
                "Invalid selected artifact descriptor for '$relativePath': $cause",
                WasmlineLoadStage.ARTIFACT_VALIDATION,
                WasmlineErrorCode.ARTIFACT_DESCRIPTOR_INVALID,
            )
        }
        if (!hostPathExists(artifactPath)) {
            return failure(
                "Artifact '$relativePath' referenced by local package '${source.path}' was not found.",
                WasmlineLoadStage.ARTIFACT_RESOLUTION,
                WasmlineErrorCode.ARTIFACT_NOT_FOUND,
            )
        }

        val identity = localArtifactIdentity(artifactPath) ?: return failure(
            "Failed to read artifact '$relativePath' referenced by local package '${source.path}'.",
            WasmlineLoadStage.ARTIFACT_RESOLUTION,
            WasmlineErrorCode.ARTIFACT_IO_FAILED,
        )
        if (identity.first != variant.sizeBytes) {
            return failure(
                "Artifact '$relativePath' has size ${identity.first}, expected ${variant.sizeBytes} bytes.",
                WasmlineLoadStage.ARTIFACT_VALIDATION,
                WasmlineErrorCode.ARTIFACT_INTEGRITY_FAILED,
            )
        }
        if (identity.second != variant.sha256) {
            return failure(
                "Artifact '$relativePath' failed SHA-256 verification. " +
                    "Expected ${variant.sha256}, actual ${identity.second}.",
                WasmlineLoadStage.ARTIFACT_VALIDATION,
                WasmlineErrorCode.ARTIFACT_INTEGRITY_FAILED,
            )
        }

        return WasmlineSourceResolution.ContinueWith(VerifiedPackageArtifact(descriptor = descriptor))
    }

    private fun localArtifactIdentity(path: String): Pair<Long, String>? {
        val fileSystem = defaultHostFileSystem()
        if (fileSystem == null) {
            val bytes = readHostFileBytes(path) ?: return null
            return bytes.size.toLong() to okio.ByteString.of(*bytes).sha256().hex()
        }
        return runCatching {
            val filePath = path.toPath()
            val size = fileSystem.metadata(filePath).size ?: error("Artifact size is unavailable.")
            val hashingSource = HashingSource.sha256(fileSystem.source(filePath))
            hashingSource.buffer().use { source ->
                val discard = Buffer()
                while (source.read(discard, STREAM_BUFFER_SIZE) != -1L) discard.clear()
            }
            size to hashingSource.hash.hex()
        }.getOrNull()
    }

    private fun describe(target: WasmlineHostArtifactTarget): String =
        "${target.operatingSystem}/${target.architecture}/${target.pointerWidth}"

    private fun failure(cause: String, stage: WasmlineLoadStage, code: WasmlineErrorCode): WasmlineSourceResolution.Complete =
        structuredResolutionFailure(stage, code, cause)

    private const val STREAM_BUFFER_SIZE: Long = 64L * 1024L
}
