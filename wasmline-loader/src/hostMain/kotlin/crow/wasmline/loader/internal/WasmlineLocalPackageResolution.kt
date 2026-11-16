@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline.loader.internal

import crow.wasmline.WasmlineLoadStage
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.loader.WasmlineLoadRequest
import crow.wasmline.loader.WasmlineSource
import crow.wasmline.loader.WasmlineSourceResolution
import crow.wasmline.loader.model.SignedManifestEnvelope
import crow.wasmline.loader.model.WasmlineManifestProtocol
import crow.wasmline.loader.toDescriptor
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
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
    /**
     * Resolves one local manifest and its selected content-addressed artifact.
     *
     * The selected artifact is copied into transient storage while it is hashed,
     * so the runtime opens the same bytes that were verified.
     */
    suspend fun resolve(
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

        val staged = stageVerifiedCopy(
            artifactPath = artifactPath,
            relativePath = relativePath,
            expectedSha256 = variant.sha256,
            expectedSizeBytes = variant.sizeBytes,
            maxArtifactBytes = request.options.maxArtifactBytes,
        )
        if (staged is LocalArtifactOutcome.Failed) return staged.resolution
        val stagedPath = (staged as LocalArtifactOutcome.Staged).path

        return WasmlineSourceResolution.ContinueWith(
            WasmlineSource.LocalArtifactPath(path = stagedPath, descriptor = descriptor.copy(path = stagedPath)),
        )
    }

    /**
     * Copies the selected artifact into transient storage while hashing it.
     *
     * The runtime reopens the returned path, so the copy must hold exactly the
     * bytes whose SHA-256 matched the signed manifest.
     */
    private suspend fun stageVerifiedCopy(
        artifactPath: String,
        relativePath: String,
        expectedSha256: String,
        expectedSizeBytes: Long,
        maxArtifactBytes: Long,
    ): LocalArtifactOutcome {
        val fileSystem = defaultHostFileSystem() ?: return LocalArtifactOutcome.Failed(
            failure(
                "Artifact '$relativePath' cannot be staged on this platform.",
                WasmlineLoadStage.ARTIFACT_RESOLUTION,
                WasmlineErrorCode.ARTIFACT_IO_FAILED,
            ),
        )
        val directory = WasmlineArtifactStaging.transientDirectory(fileSystem) ?: return LocalArtifactOutcome.Failed(
            failure(
                "No transient directory is available to stage artifact '$relativePath'.",
                WasmlineLoadStage.ARTIFACT_RESOLUTION,
                WasmlineErrorCode.ARTIFACT_IO_FAILED,
            ),
        )
        val extension = relativePath.substringAfterLast('.', missingDelimiterValue = "")
            .let { if (it.isEmpty()) ".wasm" else ".$it" }
        val target = WasmlineArtifactStaging.transientFile(directory, extension)
        val resolution = WasmlineArtifactStaging.copyVerified(
            sourcePath = artifactPath.toPath(),
            target = target,
            fileSystem = fileSystem,
            expectedSha256 = expectedSha256,
            expectedSizeBytes = expectedSizeBytes,
            maxArtifactBytes = maxArtifactBytes,
        )
        return when (resolution) {
            is ArtifactFileResolution.Ready -> {
                WasmlineTransientArtifacts.register(resolution.path)
                LocalArtifactOutcome.Staged(resolution.path)
            }

            is ArtifactFileResolution.HashMismatch -> LocalArtifactOutcome.Failed(
                failure(
                    "Artifact '$relativePath' failed SHA-256 verification. Expected $expectedSha256, actual ${resolution.actualSha256}.",
                    WasmlineLoadStage.ARTIFACT_VALIDATION,
                    WasmlineErrorCode.ARTIFACT_INTEGRITY_FAILED,
                ),
            )

            is ArtifactFileResolution.SizeMismatch -> LocalArtifactOutcome.Failed(
                failure(
                    "Artifact '$relativePath' has size ${resolution.actualSizeBytes}, expected $expectedSizeBytes bytes.",
                    WasmlineLoadStage.ARTIFACT_VALIDATION,
                    WasmlineErrorCode.ARTIFACT_INTEGRITY_FAILED,
                ),
            )

            is ArtifactFileResolution.WriteFailure -> LocalArtifactOutcome.Failed(
                failure(
                    "Failed to stage artifact '$relativePath' for the runtime: ${resolution.cause.message ?: "unknown error"}.",
                    WasmlineLoadStage.ARTIFACT_RESOLUTION,
                    WasmlineErrorCode.ARTIFACT_IO_FAILED,
                ),
            )

            is ArtifactFileResolution.HttpFailure -> LocalArtifactOutcome.Failed(
                failure(
                    "Failed to stage artifact '$relativePath' for the runtime.",
                    WasmlineLoadStage.ARTIFACT_RESOLUTION,
                    WasmlineErrorCode.ARTIFACT_IO_FAILED,
                ),
            )

            ArtifactFileResolution.Missing -> LocalArtifactOutcome.Failed(
                failure(
                    "Artifact '$relativePath' disappeared while it was staged.",
                    WasmlineLoadStage.ARTIFACT_RESOLUTION,
                    WasmlineErrorCode.ARTIFACT_IO_FAILED,
                ),
            )
        }
    }

    /** Result of staging the verified local artifact for the runtime. */
    private sealed interface LocalArtifactOutcome {
        /** Path holding exactly the verified bytes. */
        data class Staged(val path: String) : LocalArtifactOutcome

        /** Terminal failure to report to the caller. */
        data class Failed(val resolution: WasmlineSourceResolution.Complete) : LocalArtifactOutcome
    }

    private fun describe(target: WasmlineHostArtifactTarget): String =
        "${target.operatingSystem}/${target.architecture}/${target.pointerWidth}"

    private fun failure(cause: String, stage: WasmlineLoadStage, code: WasmlineErrorCode): WasmlineSourceResolution.Complete =
        structuredResolutionFailure(stage, code, cause)

    private const val STREAM_BUFFER_SIZE: Long = 64L * 1024L
}
