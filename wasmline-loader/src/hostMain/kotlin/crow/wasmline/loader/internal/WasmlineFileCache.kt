package crow.wasmline.loader.internal

import crow.wasmline.WasmlineLog
import crow.wasmline.loader.WasmlineCache
import crow.wasmline.loader.network.WasmlineNetworkClient
import okio.Buffer
import okio.FileSystem
import okio.HashingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.random.Random

/**
 * Provides file-system-backed caching with atomic publication and bounded storage.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
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
        val reservation = reserveSpace(bytes.size.toLong(), target)
        if (reservation == null) {
            WasmlineLog.logger?.warn(
                "$P Skipping cache entry '$key': ${bytes.size} bytes does not fit in maxCacheBytes=$maxCacheBytes " +
                    "without evicting an entry younger than $minimumEvictionAgeMs ms.",
            )
            return
        }
        try {
            if (writeBytesAtomically(target, bytes)) {
                removeStaleTemporaryFiles(protectedPaths = setOf(target))
            }
        } finally {
            reservation.release()
        }
    }

    /**
     * Resolves one content-addressed artifact directly to its final runtime path.
     * Cached and downloaded bytes are hashed as streams and never materialized as
     * a whole-file [ByteArray].
     */
    suspend fun resolveArtifact(
        key: String,
        extension: String,
        expectedSha256: String,
        expectedSizeBytes: Long,
        maxArtifactBytes: Long,
        networkClient: WasmlineNetworkClient?,
        artifactUrl: String,
    ): ArtifactFileResolution {
        val target = artifactPath(key, extension)
        val cachedVerification = verifyArtifact(target, expectedSha256, expectedSizeBytes, maxArtifactBytes)
        when (cachedVerification) {
            is CachedArtifactVerification.Valid -> {
                removeStaleTemporaryFiles(protectedPaths = setOf(target))
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

            is CachedArtifactVerification.SizeMismatch -> {
                WasmlineLog.logger?.warn(
                    "$P Ignoring artifact cache entry '$target' with unexpected size " +
                        "${cachedVerification.actualSizeBytes}.",
                )
                deleteQuietly(target)
                if (networkClient == null) {
                    return ArtifactFileResolution.SizeMismatch(cachedVerification.actualSizeBytes)
                }
            }

            is CachedArtifactVerification.Oversized -> {
                WasmlineLog.logger?.warn(
                    "$P Removing cache entry '$target' with ${cachedVerification.size} bytes: " +
                        "it exceeds maxCacheBytes=$maxCacheBytes.",
                )
                deleteQuietly(target)
            }

            CachedArtifactVerification.Missing -> Unit
        }

        if (networkClient == null) return ArtifactFileResolution.Missing
        // Concurrent in-process writes reserve capacity: an artifact that does not fit - because it
        // is larger than the whole limit, or because entries younger than the eviction grace
        // window still occupy the budget - goes to a transient file instead, and that file is
        // removed once the runtime has opened it.
        val reservation = reserveSpace(expectedSizeBytes, target)
        if (reservation == null) {
            return stageTransientArtifact(
                expectedSha256 = expectedSha256,
                expectedSizeBytes = expectedSizeBytes,
                maxArtifactBytes = maxArtifactBytes,
                networkClient = networkClient,
                artifactUrl = artifactUrl,
            )
        }
        return try {
            downloadArtifact(
                target = target,
                expectedSha256 = expectedSha256,
                expectedSizeBytes = expectedSizeBytes,
                maxArtifactBytes = maxArtifactBytes,
                networkClient = networkClient,
                artifactUrl = artifactUrl,
            )
        } finally {
            reservation.release()
        }
    }

    /**
     * Downloads an artifact that cannot fit in the cache into a transient file.
     *
     * The verified bytes reach the runtime through the returned path and are removed
     * after the load finishes, so `maxCacheBytes` stays a hard bound on the cache
     * directory.
     */
    private suspend fun stageTransientArtifact(
        expectedSha256: String,
        expectedSizeBytes: Long,
        maxArtifactBytes: Long,
        networkClient: WasmlineNetworkClient,
        artifactUrl: String,
    ): ArtifactFileResolution {
        val transientDirectory = WasmlineArtifactStaging.transientDirectory(fileSystem)
            ?: return ArtifactFileResolution.WriteFailure(
                IllegalStateException("No transient directory is available for an oversized artifact."),
            )
        val extension = artifactUrl.substringAfterLast('.', missingDelimiterValue = "")
        val target = WasmlineArtifactStaging.transientFile(transientDirectory, if (extension.isEmpty()) "" else ".$extension")
        val staged = WasmlineArtifactStaging.download(
            target = target,
            fileSystem = fileSystem,
            networkClient = networkClient,
            artifactUrl = artifactUrl,
            expectedSha256 = expectedSha256,
            expectedSizeBytes = expectedSizeBytes,
            maxArtifactBytes = maxArtifactBytes,
            keepBytes = false,
        )
        return when (staged) {
            is StagedArtifact.Failed -> staged.resolution

            is StagedArtifact.Ready -> {
                WasmlineTransientArtifacts.register(staged.path)
                ArtifactFileResolution.Ready(path = staged.path, cacheHit = false)
            }
        }
    }

    private suspend fun downloadArtifact(
        target: Path,
        expectedSha256: String,
        expectedSizeBytes: Long,
        maxArtifactBytes: Long,
        networkClient: WasmlineNetworkClient,
        artifactUrl: String,
    ): ArtifactFileResolution {
        val staged = WasmlineArtifactStaging.download(
            target = target,
            fileSystem = fileSystem,
            networkClient = networkClient,
            artifactUrl = artifactUrl,
            expectedSha256 = expectedSha256,
            expectedSizeBytes = expectedSizeBytes,
            maxArtifactBytes = maxArtifactBytes,
            keepBytes = false,
        )
        return when (staged) {
            is StagedArtifact.Failed -> staged.resolution

            is StagedArtifact.Ready -> {
                removeStaleTemporaryFiles(protectedPaths = setOf(target))
                ArtifactFileResolution.Ready(path = staged.path, cacheHit = false)
            }
        }
    }

    private fun verifyArtifact(
        path: Path,
        expectedSha256: String,
        expectedSizeBytes: Long,
        maxArtifactBytes: Long,
    ): CachedArtifactVerification {
        return try {
            val metadata = fileSystem.metadataOrNull(path) ?: return CachedArtifactVerification.Missing
            if (!metadata.isRegularFile) return CachedArtifactVerification.Missing
            val actualSize = metadata.size ?: return CachedArtifactVerification.Unreadable(
                IllegalStateException("Artifact size is unavailable."),
            )
            if (actualSize != expectedSizeBytes || actualSize > maxArtifactBytes) {
                return CachedArtifactVerification.SizeMismatch(actualSize)
            }
            if (actualSize > maxCacheBytes) return CachedArtifactVerification.Oversized(actualSize)
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

    /**
     * Ensures [requiredBytes] fit in the cache directory without exceeding [maxCacheBytes].
     *
     * Entries past [minimumEvictionAgeMs] are evicted oldest-first to make room. An entry
     * inside that grace window is never deleted: a concurrent load may already hold its
     * path, so when only young entries are left the caller must take a different route.
     *
     * The reservation includes concurrent writers sharing this filesystem and directory.
     * Callers release it after publication or failure, including coroutine cancellation.
     */
    private fun reserveSpace(requiredBytes: Long, target: Path): CacheReservation? = runCatching {
        CacheCapacity.lock.withLock {
            if (requiredBytes < 0 || requiredBytes > maxCacheBytes) return@withLock null
            fileSystem.createDirectories(directory)
            val identity = CacheDirectory(fileSystem, fileSystem.canonicalize(directory))
            val pending = CacheCapacity.reservations[identity].orEmpty()
            if (target.name in pending) return@withLock null
            val now = hostCurrentTimeMs()
            var entries = listEntries()
            val protectedPaths = entries.filter { entry ->
                entry.path == target || pending.keys.any { name ->
                    entry.path.name == name || entry.path.name.startsWith("$TEMPORARY_PREFIX$name-")
                }
            }.mapTo(mutableSetOf(), CacheEntry::path)
            removeStaleTemporaryFiles(entries, now, protectedPaths)
            entries = entries.filterNot { it.path.name.startsWith(TEMPORARY_PREFIX) }

            val reservationBytes = maxOf(requiredBytes, entries.firstOrNull { it.path == target }?.size ?: 0L)
            val reservedBytes = pending.values.sum()
            if (reservationBytes > maxCacheBytes || reservedBytes > maxCacheBytes - reservationBytes) return@withLock null
            val availableBytes = maxCacheBytes - reservationBytes - reservedBytes
            var totalBytes = entries.filterNot { it.path in protectedPaths }.sumOf(CacheEntry::size)

            entries
                .filterNot { it.path in protectedPaths }
                .filter { ageMs(now, it.lastUsedAt) >= minimumEvictionAgeMs }
                .sortedBy(CacheEntry::lastUsedAt)
                .forEach { entry ->
                    if (totalBytes <= availableBytes) return@forEach
                    if (deleteQuietly(entry.path)) totalBytes -= entry.size
                }
            if (totalBytes > availableBytes) return@withLock null
            CacheCapacity.reservations.getOrPut(identity) { mutableMapOf() }[target.name] = reservationBytes
            CacheReservation(identity, target.name)
        }
    }.onFailure { error ->
        WasmlineLog.logger?.warn("$P Failed to enforce cache capacity: ${error.message}")
    }.getOrNull()

    private data class CacheDirectory(val fileSystem: FileSystem, val path: Path)

    private object CacheCapacity {
        val lock = WasmlineCacheLock()
        val reservations = mutableMapOf<CacheDirectory, MutableMap<String, Long>>()
    }

    private class CacheReservation(private val directory: CacheDirectory, private val name: String) {
        fun release() = CacheCapacity.lock.withLock {
            val pending = CacheCapacity.reservations[directory] ?: return@withLock
            pending.remove(name)
            if (pending.isEmpty()) CacheCapacity.reservations.remove(directory)
        }
    }

    /** Deletes abandoned temporary files and reports the remaining entries. */
    private fun removeStaleTemporaryFiles(protectedPaths: Set<Path>) {
        runCatching {
            removeStaleTemporaryFiles(listEntries(), hostCurrentTimeMs(), protectedPaths)
        }.onFailure { error ->
            WasmlineLog.logger?.warn("$P Failed to clean temporary cache files: ${error.message}")
        }
    }

    private fun removeStaleTemporaryFiles(entries: List<CacheEntry>, now: Long, protectedPaths: Set<Path>) {
        entries.forEach { entry ->
            val isStaleTemporary = entry.path.name.startsWith(TEMPORARY_PREFIX) &&
                entry.path !in protectedPaths &&
                ageMs(now, entry.lastUsedAt) >= STALE_TEMPORARY_AGE_MS
            if (isStaleTemporary) deleteQuietly(entry.path)
        }
    }

    private fun listEntries(): List<CacheEntry> = runCatching {
        fileSystem.list(directory)
    }.getOrDefault(emptyList()).mapNotNull { path ->
        val metadata = fileSystem.metadataOrNull(path) ?: return@mapNotNull null
        if (!metadata.isRegularFile) return@mapNotNull null
        val lastUsedAt = listOfNotNull(
            metadata.lastAccessedAtMillis,
            metadata.lastModifiedAtMillis,
            metadata.createdAtMillis,
        ).maxOrNull() ?: 0L
        CacheEntry(path = path, size = metadata.size ?: 0L, lastUsedAt = lastUsedAt)
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

    /**
     * Represents the integrity state of one cached content-addressed artifact.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    private sealed interface CachedArtifactVerification {
        data object Valid : CachedArtifactVerification
        data class Corrupt(val actualSha256: String) : CachedArtifactVerification
        data class Unreadable(val cause: Throwable) : CachedArtifactVerification

        /**
         * Reports the actual size of a cached artifact that violates its declared identity.
         *
         * Date: 2026-08-28
         * Author: crowforkotlin
         */
        data class SizeMismatch(val actualSizeBytes: Long) : CachedArtifactVerification

        /** Reports a cached entry that alone exceeds the configured cache limit. */
        data class Oversized(val size: Long) : CachedArtifactVerification
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

/**
 * Represents the outcome of resolving one content-addressed artifact file.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
internal sealed interface ArtifactFileResolution {
    data class Ready(val path: String, val cacheHit: Boolean) : ArtifactFileResolution
    data class HashMismatch(val actualSha256: String) : ArtifactFileResolution

    /**
     * Reports the actual size of an artifact that violates its declared identity.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    data class SizeMismatch(val actualSizeBytes: Long) : ArtifactFileResolution
    data class HttpFailure(val statusCode: Int) : ArtifactFileResolution
    data class WriteFailure(val cause: Throwable) : ArtifactFileResolution
    data object Missing : ArtifactFileResolution
}
