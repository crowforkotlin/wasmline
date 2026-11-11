package crow.wasmline.loader.internal

import crow.wasmline.WasmlineLog
import crow.wasmline.loader.network.WasmlineNetworkClient
import crow.wasmline.loader.network.WasmlineNetworkSink
import kotlinx.coroutines.CancellationException
import okio.Buffer
import okio.ByteString
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.random.Random

/**
 * Materializes verified artifact bytes into one file with an atomic publication.
 *
 * Both the durable file cache and the transient staging area for caller-owned
 * caches use this code, so size and SHA-256 enforcement never diverge.
 *
 * Date: 2026-11-11
 * Author: crowforkotlin
 */
internal object WasmlineArtifactStaging {

    /**
     * Streams [artifactUrl] into [target] after verifying its declared size and SHA-256.
     *
     * @param keepBytes when `true`, the verified bytes are also returned so the
     *   caller can mirror them into a byte-array cache.
     */
    suspend fun download(
        target: Path,
        fileSystem: FileSystem,
        networkClient: WasmlineNetworkClient,
        artifactUrl: String,
        expectedSha256: String,
        expectedSizeBytes: Long,
        maxArtifactBytes: Long,
        keepBytes: Boolean,
    ): StagedArtifact {
        val temporary = temporaryPath(target)
        val buffer = if (keepBytes) Buffer() else null
        val hashingSink = try {
            target.parent?.let(fileSystem::createDirectories)
            HashingSink.sha256(fileSystem.sink(temporary, mustCreate = true))
        } catch (error: Exception) {
            deleteQuietly(fileSystem, temporary)
            return StagedArtifact.Failed(ArtifactFileResolution.WriteFailure(error))
        }
        val sink = hashingSink.buffer()

        var downloadedBytes = 0L
        val status = try {
            val result = networkClient.fetchTo(
                url = artifactUrl,
                sink = WasmlineNetworkSink { bytes, offset, byteCount ->
                    val nextSize = downloadedBytes + byteCount
                    if (nextSize > expectedSizeBytes || nextSize > maxArtifactBytes) {
                        throw ArtifactSizeLimitException(nextSize)
                    }
                    sink.write(bytes, offset, byteCount)
                    buffer?.write(bytes, offset, byteCount)
                    downloadedBytes = nextSize
                },
            )
            sink.close()
            result
        } catch (error: ArtifactSizeLimitException) {
            runCatching { sink.close() }
            deleteQuietly(fileSystem, temporary)
            return StagedArtifact.Failed(ArtifactFileResolution.SizeMismatch(error.actualSizeBytes))
        } catch (error: CancellationException) {
            runCatching { sink.close() }
            deleteQuietly(fileSystem, temporary)
            throw error
        } catch (error: Exception) {
            runCatching { sink.close() }
            deleteQuietly(fileSystem, temporary)
            return StagedArtifact.Failed(ArtifactFileResolution.WriteFailure(error))
        }

        if (!status.isSuccess) {
            deleteQuietly(fileSystem, temporary)
            return StagedArtifact.Failed(ArtifactFileResolution.HttpFailure(status.statusCode))
        }
        if (downloadedBytes != expectedSizeBytes) {
            deleteQuietly(fileSystem, temporary)
            return StagedArtifact.Failed(ArtifactFileResolution.SizeMismatch(downloadedBytes))
        }

        val actualSha256 = hashingSink.hash.hex()
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            deleteQuietly(fileSystem, temporary)
            return StagedArtifact.Failed(ArtifactFileResolution.HashMismatch(actualSha256))
        }

        val published = publish(fileSystem, temporary, target)
        return when (val resolution = published) {
            is ArtifactFileResolution.Ready -> StagedArtifact.Ready(resolution.path, buffer?.readByteArray())
            else -> StagedArtifact.Failed(resolution)
        }
    }

    /** Writes already verified [bytes] into [target] with the same atomic publication. */
    fun writeVerified(
        target: Path,
        fileSystem: FileSystem,
        bytes: ByteArray,
        expectedSha256: String,
        expectedSizeBytes: Long,
        maxArtifactBytes: Long,
    ): ArtifactFileResolution {
        val actualSize = bytes.size.toLong()
        if (actualSize != expectedSizeBytes || actualSize > maxArtifactBytes) {
            return ArtifactFileResolution.SizeMismatch(actualSize)
        }
        val actualSha256 = ByteString.of(*bytes).sha256().hex()
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            return ArtifactFileResolution.HashMismatch(actualSha256)
        }
        val temporary = temporaryPath(target)
        return try {
            target.parent?.let(fileSystem::createDirectories)
            fileSystem.sink(temporary, mustCreate = true).buffer().use { it.write(bytes) }
            publish(fileSystem, temporary, target)
        } catch (error: Exception) {
            deleteQuietly(fileSystem, temporary)
            ArtifactFileResolution.WriteFailure(error)
        }
    }

    /** Returns a fresh transient directory for one load operation. */
    fun transientDirectory(fileSystem: FileSystem): Path? = runCatching {
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / TRANSIENT_DIRECTORY_NAME
        fileSystem.createDirectories(directory)
        directory
    }.onFailure { error ->
        WasmlineLog.logger?.warn("[WasmlineArtifactStaging] Transient directory is unavailable: ${error.message}")
    }.getOrNull()

    /** Returns a random transient file name that cannot collide with another load. */
    fun transientFile(directory: Path, extension: String): Path =
        directory / "artifact-${Random.nextLong().toULong().toString(16)}${extension.ifBlank { ".wasm" }}"

    private fun publish(fileSystem: FileSystem, temporary: Path, target: Path): ArtifactFileResolution = try {
        fileSystem.atomicMove(temporary, target)
        ArtifactFileResolution.Ready(path = target.toString(), cacheHit = false)
    } catch (error: Exception) {
        deleteQuietly(fileSystem, temporary)
        ArtifactFileResolution.WriteFailure(error)
    }

    private fun temporaryPath(target: Path): Path {
        val parent = target.parent
        val name = ".tmp-${target.name}-${Random.nextLong()}"
        return if (parent == null) name.toPath() else parent / name
    }

    private fun deleteQuietly(fileSystem: FileSystem, path: Path) {
        runCatching { if (fileSystem.metadataOrNull(path) != null) fileSystem.delete(path) }
    }

    /**
     * Stops a streamed artifact write after its declared or configured size limit is exceeded.
     *
     * Date: 2026-11-11
     * Author: crowforkotlin
     */
    internal class ArtifactSizeLimitException(val actualSizeBytes: Long) : RuntimeException()

    private const val TRANSIENT_DIRECTORY_NAME: String = "wasmline-transient"
}

/**
 * Reports the outcome of staging one artifact file, including the verified bytes
 * when the caller asked to mirror them into a byte-array cache.
 *
 * Date: 2026-11-11
 * Author: crowforkotlin
 */
internal sealed interface StagedArtifact {
    data class Ready(val path: String, val bytes: ByteArray?) : StagedArtifact
    data class Failed(val resolution: ArtifactFileResolution) : StagedArtifact
}
