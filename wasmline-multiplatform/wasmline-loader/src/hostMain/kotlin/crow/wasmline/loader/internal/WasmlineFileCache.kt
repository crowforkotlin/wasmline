package crow.wasmline.loader.internal

import crow.wasmline.WasmlineLog
import crow.wasmline.loader.WasmlineCache
import crow.wasmline.loader.network.WasmlineNetworkClient
import crow.wasmline.loader.network.WasmlineNetworkSink
import kotlinx.coroutines.CancellationException
import okio.Buffer
import okio.FileSystem
import okio.HashingSink
import okio.HashingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.random.Random

/** File-system-backed cache with atomic publication and bounded storage. */
internal class WasmlineFileCache(
    cacheDirectory: String,
    private val maxCacheBytes: Long,
    private val fileSystem: FileSystem = requireNotNull(defaultHostFileSystem()) {
        "The platform does not expose a host file system."
    },
    private val minimumEvictionAgeMs: Long = DEFAULT_MINIMUM_EVICTION_AGE_MS,
) : WasmlineCache {
    private val directory: Path = cacheDirectory.toPath()

    init {
        require(maxCacheBytes > 0) { "maxCacheBytes must be positive" }
        require(minimumEvictionAgeMs >= 0) { "minimumEvictionAgeMs must be non-negative" }
    }

    override fun get(key: String): ByteArray? {
        val path = pathForKey(key)
        return runCatching {
            fileSystem.source(path).buffer().use { it.readByteArray() }
        }.getOrNull()
    }

    override fun put(key: String, bytes: ByteArray) {
        val target = pathForKey(key)
        if (writeBytesAtomically(target, bytes)) {
            trimToSize(protectedPaths = setOf(target))
        }
    }

    override fun exists(key: String): Boolean = runCatching {
        fileSystem.metadataOrNull(pathForKey(key))?.isRegularFile == true
    }.getOrDefault(false)

    /**
     * Resolves one content-addressed artifact directly to its final runtime path.
     * Cached and downloaded bytes are hashed as streams and never materialized as
     * a whole-file [ByteArray].
     */
    suspend fun resolveArtifact(
        key: String,
        extension: String,
        expectedSha256: String,
        networkClient: WasmlineNetworkClient?,
        artifactUrl: String,
    ): ArtifactFileResolution {
        val target = artifactPath(key, extension)
        val cachedVerification = verifyArtifact(target, expectedSha256)
        when (cachedVerification) {
            is CachedArtifactVerification.Valid -> {
                trimToSize(protectedPaths = setOf(target))
                return ArtifactFileResolution.Ready(path = target.toString(), cacheHit = true)
            }

            is CachedArtifactVerification.Corrupt -> {
                WasmlineLog.logger?.warn(
                    "$P Ignoring corrupt artifact cache entry '$target': " +
                        "expected $expectedSha256, actual ${cachedVerification.actualSha256}",
                )
                deleteQuietly(target)
                if (networkClient == null) {
                    return ArtifactFileResolution.HashMismatch(cachedVerification.actualSha256)
                }
            }

            is CachedArtifactVerification.Unreadable -> {
                WasmlineLog.logger?.warn(
                    "$P Ignoring unreadable artifact cache entry '$target': ${cachedVerification.cause.message}",
                )
                deleteQuietly(target)
            }

            CachedArtifactVerification.Missing -> Unit
        }

        if (networkClient == null) return ArtifactFileResolution.Missing
        return downloadArtifact(
            target = target,
            expectedSha256 = expectedSha256,
            networkClient = networkClient,
            artifactUrl = artifactUrl,
        )
    }

    /** Atomically materializes bytes already verified by the package resolver. */
    fun storeVerifiedArtifact(key: String, extension: String, bytes: ByteArray): String? {
        val target = artifactPath(key, extension)
        if (!writeBytesAtomically(target, bytes)) return null
        trimToSize(protectedPaths = setOf(target))
        return target.toString()
    }

    /** Streams a fresh artifact to disk without consulting an existing entry. */
    suspend fun downloadArtifact(
        key: String,
        extension: String,
        expectedSha256: String,
        networkClient: WasmlineNetworkClient,
        artifactUrl: String,
    ): ArtifactFileResolution = downloadArtifact(
        target = artifactPath(key, extension),
        expectedSha256 = expectedSha256,
        networkClient = networkClient,
        artifactUrl = artifactUrl,
    )

    private suspend fun downloadArtifact(
        target: Path,
        expectedSha256: String,
        networkClient: WasmlineNetworkClient,
        artifactUrl: String,
    ): ArtifactFileResolution {
        val temporary = temporaryPath(target)
        val hashingSink = try {
            fileSystem.createDirectories(directory)
            HashingSink.sha256(fileSystem.sink(temporary, mustCreate = true))
        } catch (error: Exception) {
            deleteQuietly(temporary)
            return ArtifactFileResolution.WriteFailure(error)
        }
        val sink = hashingSink.buffer()

        val status = try {
            val result = networkClient.fetchTo(
                url = artifactUrl,
                sink = WasmlineNetworkSink { bytes, offset, byteCount ->
                    sink.write(bytes, offset, byteCount)
                },
            )
            sink.close()
            result
        } catch (error: CancellationException) {
            runCatching { sink.close() }
            deleteQuietly(temporary)
            throw error
        } catch (error: Exception) {
            runCatching { sink.close() }
            deleteQuietly(temporary)
            return ArtifactFileResolution.WriteFailure(error)
        }

        if (!status.isSuccess) {
            deleteQuietly(temporary)
            return ArtifactFileResolution.HttpFailure(status.statusCode)
        }

        val actualSha256 = hashingSink.hash.hex()
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            deleteQuietly(temporary)
            return ArtifactFileResolution.HashMismatch(actualSha256)
        }

        return try {
            fileSystem.atomicMove(temporary, target)
            trimToSize(protectedPaths = setOf(target))
            ArtifactFileResolution.Ready(path = target.toString(), cacheHit = false)
        } catch (error: Exception) {
            deleteQuietly(temporary)
            ArtifactFileResolution.WriteFailure(error)
        }
    }

    private fun verifyArtifact(path: Path, expectedSha256: String): CachedArtifactVerification {
        return try {
            if (fileSystem.metadataOrNull(path)?.isRegularFile != true) return CachedArtifactVerification.Missing
            val hashingSource = HashingSource.sha256(fileSystem.source(path))
            hashingSource.buffer().use { source ->
                val discard = Buffer()
                while (source.read(discard, STREAM_BUFFER_SIZE) != -1L) {
                    discard.clear()
                }
            }
            val actualSha256 = hashingSource.hash.hex()
            if (actualSha256.equals(expectedSha256, ignoreCase = true)) {
                CachedArtifactVerification.Valid
            } else {
                CachedArtifactVerification.Corrupt(actualSha256)
            }
        } catch (error: Exception) {
            CachedArtifactVerification.Unreadable(error)
        }
    }

    private fun writeBytesAtomically(target: Path, bytes: ByteArray): Boolean {
        val temporary = temporaryPath(target)
        return try {
            fileSystem.createDirectories(directory)
            fileSystem.sink(temporary, mustCreate = true).buffer().use { it.write(bytes) }
            fileSystem.atomicMove(temporary, target)
            true
        } catch (error: Exception) {
            WasmlineLog.logger?.warn("$P Failed to write cache entry '$target': ${error.message}")
            deleteQuietly(temporary)
            false
        }
    }

    private fun trimToSize(protectedPaths: Set<Path>) {
        runCatching {
            val now = hostCurrentTimeMs()
            val entries = fileSystem.list(directory).mapNotNull { path ->
                val metadata = fileSystem.metadataOrNull(path) ?: return@mapNotNull null
                if (!metadata.isRegularFile) return@mapNotNull null
                val lastUsedAt = listOfNotNull(
                    metadata.lastAccessedAtMillis,
                    metadata.lastModifiedAtMillis,
                    metadata.createdAtMillis,
                ).maxOrNull() ?: 0L
                CacheEntry(path = path, size = metadata.size ?: 0L, lastUsedAt = lastUsedAt)
            }.toMutableList()

            entries.removeAll { entry ->
                val isStaleTemporary = entry.path.name.startsWith(TEMPORARY_PREFIX) &&
                    entry.path !in protectedPaths &&
                    ageMs(now, entry.lastUsedAt) >= STALE_TEMPORARY_AGE_MS
                isStaleTemporary && deleteQuietly(entry.path)
            }

            var totalBytes = entries.sumOf(CacheEntry::size)
            if (totalBytes <= maxCacheBytes) return@runCatching

            entries.asSequence()
                .filterNot { it.path.name.startsWith(TEMPORARY_PREFIX) }
                .filterNot { it.path in protectedPaths }
                .filter { ageMs(now, it.lastUsedAt) >= minimumEvictionAgeMs }
                .sortedBy(CacheEntry::lastUsedAt)
                .forEach { entry ->
                    if (totalBytes <= maxCacheBytes) return@forEach
                    if (deleteQuietly(entry.path)) totalBytes -= entry.size
                }
        }.onFailure { error ->
            WasmlineLog.logger?.warn("$P Failed to enforce cache capacity: ${error.message}")
        }
    }

    private fun pathForKey(key: String): Path {
        require(key != "." && key != ".." && SAFE_KEY.matches(key)) { "Invalid Wasmline cache key: '$key'" }
        return directory / key
    }

    private fun artifactPath(key: String, extension: String): Path {
        require(SAFE_EXTENSION.matches(extension)) { "Invalid Wasmline artifact extension: '$extension'" }
        return pathForKey("$key$extension")
    }

    private fun temporaryPath(target: Path): Path =
        directory / "$TEMPORARY_PREFIX${target.name}-${hostCurrentTimeMs()}-${Random.nextLong()}"

    private fun deleteQuietly(path: Path): Boolean = runCatching {
        if (fileSystem.metadataOrNull(path) == null) return@runCatching false
        fileSystem.delete(path)
        true
    }.getOrDefault(false)

    private fun ageMs(now: Long, timestamp: Long): Long = (now - timestamp).coerceAtLeast(0L)

    private data class CacheEntry(val path: Path, val size: Long, val lastUsedAt: Long)

    private sealed interface CachedArtifactVerification {
        data object Valid : CachedArtifactVerification
        data class Corrupt(val actualSha256: String) : CachedArtifactVerification
        data class Unreadable(val cause: Throwable) : CachedArtifactVerification
        data object Missing : CachedArtifactVerification
    }

    private companion object {
        const val P: String = "[WasmlineFileCache]"
        const val STREAM_BUFFER_SIZE: Long = 64L * 1024L
        const val DEFAULT_MINIMUM_EVICTION_AGE_MS: Long = 60_000L
        const val STALE_TEMPORARY_AGE_MS: Long = 24L * 60L * 60L * 1000L
        const val TEMPORARY_PREFIX: String = ".tmp-"
        val SAFE_KEY: Regex = Regex("^[A-Za-z0-9._-]+$")
        val SAFE_EXTENSION: Regex = Regex("^\\.[A-Za-z0-9.]+$")
    }
}

internal sealed interface ArtifactFileResolution {
    data class Ready(val path: String, val cacheHit: Boolean) : ArtifactFileResolution
    data class HashMismatch(val actualSha256: String) : ArtifactFileResolution
    data class HttpFailure(val statusCode: Int) : ArtifactFileResolution
    data class WriteFailure(val cause: Throwable) : ArtifactFileResolution
    data object Missing : ArtifactFileResolution
}
