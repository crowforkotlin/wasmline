@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline.loader.internal

import crow.wasmline.WasmlineLoadStage
import crow.wasmline.WasmlineLog
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.loader.VerifiedPackageArtifact
import crow.wasmline.loader.WasmlineCache
import crow.wasmline.loader.WasmlineLoadRequest
import crow.wasmline.loader.WasmlineNoOpCache
import crow.wasmline.loader.WasmlineSource
import crow.wasmline.loader.WasmlineSourceResolution
import crow.wasmline.loader.model.SignedManifestEnvelope
import crow.wasmline.loader.model.WasmlineManifestProtocol
import crow.wasmline.loader.network.WasmlineNetworkClient
import crow.wasmline.loader.toDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.HashingSink
import okio.buffer

/**
 * Resolves remote signed manifests and one selected content-addressed artifact.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
internal object WasmlineRemotePackageResolution {
    private const val P: String = "[WasmlineRemotePackageResolution]"

    private val artifactLocksGuard = Mutex()
    private val artifactLocks = mutableMapOf<String, ArtifactLockEntry>()

    /** Resolves a remote package without downloading unselected artifact variants. */
    suspend fun resolve(
        source: WasmlineSource.RemoteManifestUrl,
        request: WasmlineLoadRequest,
        host: WasmlineHostArtifactTarget? = null,
    ): WasmlineSourceResolution {
        val options = request.options
        val networkClient = options.networkClient
        val defaultFileCache = defaultFileCacheOrNull(options.maxCacheBytes)
        val cache = options.cache ?: defaultFileCache
        val manifestUrl = resolveManifestUrl(source.url)
        WasmlineLog.logger?.info("$P Resolving remote package: $manifestUrl")

        val manifestCacheKey = "manifest_${sha256Hex(manifestUrl.encodeToByteArray())}"
        val manifestTimestampKey = "$manifestCacheKey.timestamp"
        val manifestBytes = try {
            resolveManifestCached(
                cache = cache,
                manifestCacheKey = manifestCacheKey,
                manifestTimestampKey = manifestTimestampKey,
                manifestTtlMs = options.manifestTtlMs,
                maxManifestBytes = options.manifestLimits.maxManifestBytes,
                networkClient = networkClient,
                manifestUrl = manifestUrl,
            )
        } catch (_: ManifestSizeLimitException) {
            return failure(
                "Manifest '$manifestUrl' exceeds the configured manifest byte limit.",
                WasmlineLoadStage.MANIFEST_DECODING,
                WasmlineErrorCode.MANIFEST_INVALID,
            )
        } ?: return if (networkClient == null) {
            failure(
                "Manifest '$manifestUrl' is not available in a fresh cache entry. " +
                    "Provide request.options.networkClient or request.resolvers.remotePackage.",
                WasmlineLoadStage.SOURCE_RESOLUTION,
                WasmlineErrorCode.ARTIFACT_NOT_FOUND,
            )
        } else {
            failure(
                "Failed to fetch manifest from '$manifestUrl'.",
                WasmlineLoadStage.SOURCE_RESOLUTION,
                WasmlineErrorCode.ARTIFACT_DOWNLOAD_FAILED,
            )
        }
        if (manifestBytes.size > options.manifestLimits.maxManifestBytes) {
            return failure(
                "Manifest '$manifestUrl' exceeds the configured manifest byte limit.",
                WasmlineLoadStage.MANIFEST_DECODING,
                WasmlineErrorCode.MANIFEST_INVALID,
            )
        }

        val envelope = try {
            ProtoBuf.decodeFromByteArray(SignedManifestEnvelope.serializer(), manifestBytes)
        } catch (_: Exception) {
            return failure(
                "Failed to parse manifest envelope from '$manifestUrl'.",
                WasmlineLoadStage.MANIFEST_DECODING,
                WasmlineErrorCode.MANIFEST_INVALID,
            )
        }
        val manifest = when (
            val verification = WasmlinePackageSignatureVerifier.verify(
                envelope = envelope,
                trustedKeys = options.trustedKeys,
                packageLocation = manifestUrl,
                limits = options.manifestLimits,
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
                "No compatible artifact found in remote package '$manifestUrl' for host " +
                    describe(resolvedHost) + ".",
                WasmlineLoadStage.ARTIFACT_SELECTION,
                WasmlineErrorCode.ARTIFACT_NOT_COMPATIBLE,
            )
        }

        val variant = selected.variant
        if (variant.sizeBytes > options.maxArtifactBytes) {
            return failure(
                "Selected artifact '${variant.sha256}' exceeds the configured artifact byte limit.",
                WasmlineLoadStage.ARTIFACT_VALIDATION,
                WasmlineErrorCode.ARTIFACT_INTEGRITY_FAILED,
            )
        }
        val relativePath = WasmlineManifestProtocol.artifactRelativePath(variant.sha256, selected.target.format)
        val pendingDescriptor = selected.toDescriptor("pending", manifest.runtimeContract)
        pendingDescriptor.validationError()?.let { cause ->
            return failure(
                "Invalid selected artifact descriptor for '$relativePath': $cause",
                WasmlineLoadStage.ARTIFACT_VALIDATION,
                WasmlineErrorCode.ARTIFACT_DESCRIPTOR_INVALID,
            )
        }

        val artifactUrl = resolveContentAddressedUrl(manifestUrl, relativePath)
        val artifactCacheKey = "artifact_${variant.sha256}"
        val extension = ".${WasmlineManifestProtocol.artifactExtension(selected.target.format)}"
        val resolution = withArtifactLock("$artifactCacheKey$extension") {
            resolveArtifactFile(
                cache = cache,
                defaultFileCache = defaultFileCache,
                cacheKey = artifactCacheKey,
                extension = extension,
                networkClient = networkClient,
                artifactUrl = artifactUrl,
                expectedSha256 = variant.sha256,
                expectedSizeBytes = variant.sizeBytes,
                maxArtifactBytes = options.maxArtifactBytes,
            )
        }
        val localPath = when (resolution) {
            is ArtifactFileResolution.Ready -> {
                val outcome = if (resolution.cacheHit) "hit" else "stored"
                WasmlineLog.logger?.debug("$P Artifact cache $outcome: $artifactCacheKey")
                resolution.path
            }

            is ArtifactFileResolution.HashMismatch -> return failure(
                "Artifact '$relativePath' failed SHA-256 verification. " +
                    "Expected ${variant.sha256}, actual ${resolution.actualSha256}.",
                WasmlineLoadStage.ARTIFACT_VALIDATION,
                WasmlineErrorCode.ARTIFACT_INTEGRITY_FAILED,
            )

            is ArtifactFileResolution.SizeMismatch -> return failure(
                "Artifact '$relativePath' has size ${resolution.actualSizeBytes}, expected ${variant.sizeBytes} bytes.",
                WasmlineLoadStage.ARTIFACT_VALIDATION,
                WasmlineErrorCode.ARTIFACT_INTEGRITY_FAILED,
            )

            is ArtifactFileResolution.HttpFailure -> return failure(
                "Failed to fetch artifact '$artifactUrl': HTTP ${resolution.statusCode}.",
                WasmlineLoadStage.ARTIFACT_RESOLUTION,
                WasmlineErrorCode.ARTIFACT_DOWNLOAD_FAILED,
            )

            is ArtifactFileResolution.WriteFailure -> return failure(
                "Failed to fetch or atomically cache artifact '$artifactUrl': " +
                    (resolution.cause.message ?: "unknown error"),
                WasmlineLoadStage.ARTIFACT_RESOLUTION,
                WasmlineErrorCode.ARTIFACT_IO_FAILED,
            )

            ArtifactFileResolution.Missing -> return if (networkClient == null) {
                failure(
                    "Artifact '$artifactUrl' is not available in cache. " +
                        "Provide request.options.networkClient or request.resolvers.remotePackage.",
                    WasmlineLoadStage.ARTIFACT_RESOLUTION,
                    WasmlineErrorCode.ARTIFACT_NOT_FOUND,
                )
            } else {
                failure(
                    "Failed to fetch artifact from '$artifactUrl'.",
                    WasmlineLoadStage.ARTIFACT_RESOLUTION,
                    WasmlineErrorCode.ARTIFACT_DOWNLOAD_FAILED,
                )
            }
        }

        return WasmlineSourceResolution.ContinueWith(
            VerifiedPackageArtifact(selected.toDescriptor(localPath, manifest.runtimeContract)),
        )
    }

    private suspend fun resolveManifestCached(
        cache: WasmlineCache?,
        manifestCacheKey: String,
        manifestTimestampKey: String,
        manifestTtlMs: Long,
        maxManifestBytes: Int,
        networkClient: WasmlineNetworkClient?,
        manifestUrl: String,
    ): ByteArray? {
        val log = WasmlineLog.logger
        if (cache != null && manifestTtlMs > 0) {
            val cached = cache.get(manifestCacheKey)
            val fetchedAt = cache.get(manifestTimestampKey)?.decodeToString()?.toLongOrNull()
            val cacheAgeMs = fetchedAt?.let { currentTimeMs() - it }
            if (cached != null && cacheAgeMs != null && cacheAgeMs in 0..manifestTtlMs) {
                log?.debug("$P Manifest cache hit: $manifestCacheKey")
                return cached
            }
        }
        if (networkClient == null) return null
        log?.debug("$P Manifest cache miss, fetching: $manifestUrl")
        val bytes = fetchBytes(networkClient, manifestUrl, maxManifestBytes) ?: return null
        cache?.put(manifestCacheKey, bytes)
        cache?.put(manifestTimestampKey, currentTimeMs().toString().encodeToByteArray())
        return bytes
    }

    private suspend fun resolveArtifactFile(
        cache: WasmlineCache?,
        defaultFileCache: WasmlineFileCache?,
        cacheKey: String,
        extension: String,
        networkClient: WasmlineNetworkClient?,
        artifactUrl: String,
        expectedSha256: String,
        expectedSizeBytes: Long,
        maxArtifactBytes: Long,
    ): ArtifactFileResolution {
        if (cache is WasmlineFileCache) {
            return cache.resolveArtifact(
                key = cacheKey,
                extension = extension,
                expectedSha256 = expectedSha256,
                expectedSizeBytes = expectedSizeBytes,
                maxArtifactBytes = maxArtifactBytes,
                networkClient = networkClient,
                artifactUrl = artifactUrl,
            )
        }
        if (cache != null && cache !== WasmlineNoOpCache) {
            when (
                val customCached = resolveCustomCachedArtifact(
                    cache = cache,
                    cacheKey = cacheKey,
                    extension = extension,
                    defaultFileCache = defaultFileCache,
                    platformCacheKey = artifactUrl,
                    expectedSha256 = expectedSha256,
                    expectedSizeBytes = expectedSizeBytes,
                    maxArtifactBytes = maxArtifactBytes,
                )
            ) {
                is ArtifactFileResolution.Ready -> return customCached

                is ArtifactFileResolution.HashMismatch,
                is ArtifactFileResolution.SizeMismatch,
                -> if (networkClient == null) return customCached

                is ArtifactFileResolution.WriteFailure -> if (networkClient == null) return customCached

                is ArtifactFileResolution.HttpFailure,
                ArtifactFileResolution.Missing,
                null,
                -> Unit
            }
        }
        if (defaultFileCache == null) {
            return resolvePlatformCachedArtifact(
                cache = cache,
                cacheKey = cacheKey,
                networkClient = networkClient,
                artifactUrl = artifactUrl,
                expectedSha256 = expectedSha256,
                expectedSizeBytes = expectedSizeBytes,
                maxArtifactBytes = maxArtifactBytes,
            )
        }
        val stagingCache = defaultFileCache
        if (cache === WasmlineNoOpCache || cache == null) {
            if (networkClient == null) return ArtifactFileResolution.Missing
            return stagingCache.downloadArtifact(
                key = cacheKey,
                extension = extension,
                expectedSha256 = expectedSha256,
                expectedSizeBytes = expectedSizeBytes,
                maxArtifactBytes = maxArtifactBytes,
                networkClient = networkClient,
                artifactUrl = artifactUrl,
            )
        }

        return stagingCache.resolveArtifact(
            key = cacheKey,
            extension = extension,
            expectedSha256 = expectedSha256,
            expectedSizeBytes = expectedSizeBytes,
            maxArtifactBytes = maxArtifactBytes,
            networkClient = networkClient,
            artifactUrl = artifactUrl,
        )
    }

    private fun resolveCustomCachedArtifact(
        cache: WasmlineCache,
        cacheKey: String,
        extension: String,
        defaultFileCache: WasmlineFileCache?,
        platformCacheKey: String,
        expectedSha256: String,
        expectedSizeBytes: Long,
        maxArtifactBytes: Long,
    ): ArtifactFileResolution? {
        val bytes = runCatching { cache.get(cacheKey) }
            .onFailure { error -> WasmlineLog.logger?.warn("$P Custom artifact cache read failed: ${error.message}") }
            .getOrNull() ?: return null
        val actualSize = bytes.size.toLong()
        if (actualSize != expectedSizeBytes || actualSize > maxArtifactBytes) {
            return ArtifactFileResolution.SizeMismatch(actualSize)
        }
        val actualSha256 = sha256Hex(bytes)
        if (actualSha256 != expectedSha256) return ArtifactFileResolution.HashMismatch(actualSha256)

        val path = if (defaultFileCache != null) {
            defaultFileCache.storeVerifiedArtifact(cacheKey, extension, bytes)
        } else if (cachePlatformResolvedArtifact(platformCacheKey, bytes)) {
            platformCacheKey
        } else {
            null
        }
        return if (path != null) {
            ArtifactFileResolution.Ready(path = path, cacheHit = true)
        } else {
            ArtifactFileResolution.WriteFailure(
                IllegalStateException("Failed to materialize the custom artifact cache entry."),
            )
        }
    }

    private suspend fun resolvePlatformCachedArtifact(
        cache: WasmlineCache?,
        cacheKey: String,
        networkClient: WasmlineNetworkClient?,
        artifactUrl: String,
        expectedSha256: String,
        expectedSizeBytes: Long,
        maxArtifactBytes: Long,
    ): ArtifactFileResolution {
        if (platformResolvedArtifactExists(artifactUrl)) {
            return ArtifactFileResolution.Ready(path = artifactUrl, cacheHit = true)
        }
        if (networkClient == null) return ArtifactFileResolution.Missing
        if (expectedSizeBytes > Int.MAX_VALUE) {
            return ArtifactFileResolution.WriteFailure(
                IllegalArgumentException("The selected Web artifact exceeds the platform byte-array limit."),
            )
        }

        val buffer = Buffer()
        val hashingSink = HashingSink.sha256(buffer)
        val sink = hashingSink.buffer()
        var received = 0L
        return try {
            val status = networkClient.fetchTo(
                url = artifactUrl,
                sink = { bytes, offset, byteCount ->
                    received += byteCount
                    if (received > maxArtifactBytes || received > expectedSizeBytes) {
                        throw IllegalStateException("Selected Web artifact exceeded its declared size.")
                    }
                    sink.write(bytes, offset, byteCount)
                },
            )
            sink.flush()
            if (!status.isSuccess) return ArtifactFileResolution.HttpFailure(status.statusCode)
            if (received != expectedSizeBytes) return ArtifactFileResolution.SizeMismatch(received)
            val actualSha256 = hashingSink.hash.hex()
            if (actualSha256 != expectedSha256) return ArtifactFileResolution.HashMismatch(actualSha256)
            val bytes = buffer.readByteArray()
            if (!cachePlatformResolvedArtifact(artifactUrl, bytes)) {
                ArtifactFileResolution.WriteFailure(
                    IllegalStateException("The platform cannot cache the selected artifact bytes."),
                )
            } else {
                if (cache != null && cache !== WasmlineNoOpCache) {
                    runCatching { cache.put(cacheKey, bytes) }
                        .onFailure { error -> WasmlineLog.logger?.warn("$P Custom artifact cache write failed: ${error.message}") }
                }
                ArtifactFileResolution.Ready(path = artifactUrl, cacheHit = false)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (received > maxArtifactBytes || received > expectedSizeBytes) {
                ArtifactFileResolution.SizeMismatch(received)
            } else {
                ArtifactFileResolution.WriteFailure(error)
            }
        }
    }

    private suspend fun <T> withArtifactLock(key: String, block: suspend () -> T): T {
        val entry = artifactLocksGuard.withLock {
            artifactLocks.getOrPut(key) { ArtifactLockEntry() }.also { it.users++ }
        }
        return try {
            entry.mutex.lock()
            try {
                block()
            } finally {
                entry.mutex.unlock()
            }
        } finally {
            withContext(NonCancellable) {
                artifactLocksGuard.withLock {
                    entry.users--
                    if (entry.users == 0 && artifactLocks[key] === entry) artifactLocks.remove(key)
                }
            }
        }
    }

    private suspend fun fetchBytes(networkClient: WasmlineNetworkClient, url: String, maxBytes: Int): ByteArray? = try {
        val buffer = Buffer()
        var received = 0L
        val status = networkClient.fetchTo(
            url = url,
            sink = { bytes, offset, byteCount ->
                received += byteCount
                if (received > maxBytes) throw ManifestSizeLimitException()
                buffer.write(bytes, offset, byteCount)
            },
        )
        if (status.isSuccess) buffer.readByteArray() else null
    } catch (error: CancellationException) {
        throw error
    } catch (error: ManifestSizeLimitException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun resolveManifestUrl(sourceUrl: String): String {
        val url = sourceUrl.trim()
        return if (url.endsWith(".wlm")) url else url.trimEnd('/') + "/$MANIFEST_FILE_NAME"
    }

    private fun resolveContentAddressedUrl(manifestUrl: String, relativePath: String): String {
        val baseUrl = manifestUrl.substringBeforeLast('/', missingDelimiterValue = "")
        return if (baseUrl.isEmpty()) relativePath else "$baseUrl/$relativePath"
    }

    private fun defaultFileCacheOrNull(maxCacheBytes: Long): WasmlineFileCache? =
        defaultCacheDirectory()?.let { WasmlineFileCache(it, maxCacheBytes) }

    private fun currentTimeMs(): Long = hostCurrentTimeMs()

    private fun sha256Hex(bytes: ByteArray): String = bytes.toByteString().sha256().hex()

    private fun describe(target: WasmlineHostArtifactTarget): String =
        "${target.operatingSystem}/${target.architecture}/${target.pointerWidth}"

    private fun failure(cause: String, stage: WasmlineLoadStage, code: WasmlineErrorCode): WasmlineSourceResolution.Complete =
        structuredResolutionFailure(stage, code, cause)

    /**
     * Tracks one keyed artifact download mutex and active users.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    private class ArtifactLockEntry(val mutex: Mutex = Mutex(), var users: Int = 0)

    /**
     * Signals that a streamed manifest exceeded its configured byte limit.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    private class ManifestSizeLimitException : IllegalStateException()
}
