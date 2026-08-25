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
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineArtifactType
import crow.wasmline.loader.network.WasmlineNetworkClient
import crow.wasmline.loader.toDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import okio.ByteString.Companion.toByteString

/** Built-in remote manifest, artifact verification, and cache pipeline. */
internal object WasmlineRemotePackageResolution {
    private const val P = "[WasmlineRemotePackageResolution]"

    private val artifactLocksGuard = Mutex()
    private val artifactLocks = mutableMapOf<String, ArtifactLockEntry>()

    suspend fun resolve(source: WasmlineSource.RemoteManifestUrl, request: WasmlineLoadRequest): WasmlineSourceResolution {
        val options = request.options
        val networkClient = options.networkClient
        val defaultFileCache = defaultFileCacheOrNull(options.maxCacheBytes)
        val cache = options.cache ?: defaultFileCache
        val manifestUrl = resolveManifestUrl(source.url)
        WasmlineLog.logger?.info("$P Resolving remote package: $manifestUrl")

        val manifestCacheKey = "manifest_${sha256Hex(manifestUrl.encodeToByteArray())}"
        val manifestTimestampKey = "$manifestCacheKey.timestamp"
        val manifestBytes = resolveManifestCached(
            cache = cache,
            manifestCacheKey = manifestCacheKey,
            manifestTimestampKey = manifestTimestampKey,
            manifestTtlMs = options.manifestTtlMs,
            networkClient = networkClient,
            manifestUrl = manifestUrl,
        ) ?: return if (networkClient == null) {
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
            )
        ) {
            is WasmlineManifestVerification.Verified -> verification.manifest

            is WasmlineManifestVerification.Rejected -> return failure(
                verification.cause,
                WasmlineLoadStage.SIGNATURE_VERIFICATION,
                WasmlineErrorCode.SIGNATURE_VERIFICATION_FAILED,
            )
        }

        val artifact = WasmlineLocalPackageResolution.selectArtifact(manifest.artifacts)
            ?: return failure(
                "No compatible artifact found in remote package '$manifestUrl' " +
                    "for host ${describe(currentHostArtifactTarget)}.",
                WasmlineLoadStage.ARTIFACT_SELECTION,
                WasmlineErrorCode.ARTIFACT_NOT_COMPATIBLE,
            )
        val pendingDescriptor = artifact.toDescriptor(path = "pending")
        pendingDescriptor.validationError()?.let {
            return failure(
                "Invalid artifact descriptor for '${artifact.url}': $it",
                WasmlineLoadStage.ARTIFACT_VALIDATION,
                WasmlineErrorCode.ARTIFACT_DESCRIPTOR_INVALID,
            )
        }
        if (artifact.sha256.isBlank()) {
            return failure(
                "Artifact '${artifact.url}' from '$manifestUrl' is missing sha256 metadata.",
                WasmlineLoadStage.ARTIFACT_VALIDATION,
                WasmlineErrorCode.ARTIFACT_INTEGRITY_FAILED,
            )
        }
        if (!sha256Pattern.matches(artifact.sha256)) {
            return failure(
                "Artifact '${artifact.url}' from '$manifestUrl' has invalid sha256 metadata.",
                WasmlineLoadStage.ARTIFACT_VALIDATION,
                WasmlineErrorCode.ARTIFACT_INTEGRITY_FAILED,
            )
        }

        val artifactUrl = resolveArtifactUrl(manifestUrl, artifact.url)
        val expectedSha256 = artifact.sha256.lowercase()
        val artifactCacheKey = "artifact_$expectedSha256"
        val extension = artifactExtension(artifact.type)
        val resolution = withArtifactLock("$artifactCacheKey$extension") {
            resolveArtifactFile(
                cache = cache,
                defaultFileCache = defaultFileCache,
                cacheKey = artifactCacheKey,
                extension = extension,
                networkClient = networkClient,
                artifactUrl = artifactUrl,
                expectedSha256 = expectedSha256,
            )
        }
        val localPath = when (resolution) {
            is ArtifactFileResolution.Ready -> {
                val outcome = if (resolution.cacheHit) "hit" else "stored"
                WasmlineLog.logger?.debug("$P Artifact cache $outcome: $artifactCacheKey")
                resolution.path
            }

            is ArtifactFileResolution.HashMismatch -> return failure(
                "Artifact '${artifact.url}' from '$artifactUrl' failed sha256 verification. " +
                    "Expected $expectedSha256, actual ${resolution.actualSha256}.",
                WasmlineLoadStage.SIGNATURE_VERIFICATION,
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
            VerifiedPackageArtifact(
                descriptor = artifact.toDescriptor(localPath),
            ),
        )
    }

    private suspend fun resolveManifestCached(
        cache: WasmlineCache?,
        manifestCacheKey: String,
        manifestTimestampKey: String,
        manifestTtlMs: Long,
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
            if (cached != null) {
                log?.debug("$P Manifest cache expired or has no timestamp: $manifestCacheKey")
            }
        }

        if (networkClient == null) return null
        log?.debug("$P Manifest cache miss, fetching: $manifestUrl")
        val bytes = fetchBytes(networkClient, manifestUrl) ?: return null
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
    ): ArtifactFileResolution {
        if (cache is WasmlineFileCache) {
            return cache.resolveArtifact(
                key = cacheKey,
                extension = extension,
                expectedSha256 = expectedSha256,
                networkClient = networkClient,
                artifactUrl = artifactUrl,
            )
        }

        val stagingCache = defaultFileCache
            ?: return ArtifactFileResolution.WriteFailure(
                IllegalStateException("The platform default cache directory is unavailable."),
            )

        if (cache === WasmlineNoOpCache || cache == null) {
            if (networkClient == null) return ArtifactFileResolution.Missing
            return stagingCache.downloadArtifact(
                key = cacheKey,
                extension = extension,
                expectedSha256 = expectedSha256,
                networkClient = networkClient,
                artifactUrl = artifactUrl,
            )
        }

        val cachedBytes = runCatching { cache.get(cacheKey) }
            .onFailure { WasmlineLog.logger?.warn("$P Custom artifact cache read failed: ${it.message}") }
            .getOrNull()
        if (cachedBytes != null) {
            val actualSha256 = sha256Hex(cachedBytes)
            if (actualSha256.equals(expectedSha256, ignoreCase = true)) {
                val path = stagingCache.storeVerifiedArtifact(cacheKey, extension, cachedBytes)
                    ?: return ArtifactFileResolution.WriteFailure(
                        IllegalStateException("Failed to materialize the custom artifact cache entry."),
                    )
                return ArtifactFileResolution.Ready(path = path, cacheHit = true)
            }
            WasmlineLog.logger?.warn(
                "$P Ignoring corrupt custom artifact cache entry $cacheKey: " +
                    "expected $expectedSha256, actual $actualSha256",
            )
            if (networkClient == null) return ArtifactFileResolution.HashMismatch(actualSha256)
        }

        if (networkClient == null) return ArtifactFileResolution.Missing
        WasmlineLog.logger?.debug("$P Custom artifact cache miss, fetching: $artifactUrl")
        val downloaded = fetchBytes(networkClient, artifactUrl) ?: return ArtifactFileResolution.Missing
        val actualSha256 = sha256Hex(downloaded)
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            return ArtifactFileResolution.HashMismatch(actualSha256)
        }
        val path = stagingCache.storeVerifiedArtifact(cacheKey, extension, downloaded)
            ?: return ArtifactFileResolution.WriteFailure(
                IllegalStateException("Failed to materialize the downloaded artifact."),
            )
        runCatching { cache.put(cacheKey, downloaded) }
            .onFailure { WasmlineLog.logger?.warn("$P Custom artifact cache write failed: ${it.message}") }
        return ArtifactFileResolution.Ready(path = path, cacheHit = false)
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

    private fun currentTimeMs(): Long = hostCurrentTimeMs()

    private fun resolveManifestUrl(sourceUrl: String): String {
        val url = sourceUrl.trim()
        return if (url.endsWith(".wlm")) url else url.trimEnd('/') + "/$MANIFEST_FILE_NAME"
    }

    private suspend fun fetchBytes(networkClient: WasmlineNetworkClient, url: String): ByteArray? = try {
        val response = networkClient.fetch(url)
        if (!response.isSuccess) {
            WasmlineLog.logger?.warn("$P HTTP ${response.statusCode} for $url")
            null
        } else {
            response.bytes
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        WasmlineLog.logger?.error("$P Network error fetching $url: ${error.message}")
        null
    }

    private fun resolveArtifactUrl(manifestUrl: String, artifactUrl: String): String {
        if (artifactUrl.startsWith("http://") || artifactUrl.startsWith("https://")) return artifactUrl
        val baseUrl = manifestUrl.substringBeforeLast('/', missingDelimiterValue = "")
        return if (baseUrl.isEmpty()) artifactUrl else "$baseUrl/$artifactUrl"
    }

    private fun artifactExtension(type: WasmlineArtifactType): String = when (type) {
        WasmlineArtifactType.WASM -> ".wasm"
        WasmlineArtifactType.CWASM -> ".cwasm"
        WasmlineArtifactType.PWASM -> ".pwasm"
        WasmlineArtifactType.COMPONENT_WASM -> ".component.wasm"
    }

    private fun defaultFileCacheOrNull(maxCacheBytes: Long): WasmlineFileCache? {
        val directory = defaultCacheDirectory() ?: return null
        return WasmlineFileCache(cacheDirectory = directory, maxCacheBytes = maxCacheBytes)
    }

    private fun sha256Hex(bytes: ByteArray): String = bytes.toByteString().sha256().hex()

    private fun describe(target: WasmlineHostArtifactTarget): String {
        val bitness = if (target.is64Bit) "64-bit" else "32-bit"
        return "${target.os}/${target.cpu} ($bitness)"
    }

    private fun failure(
        cause: String,
        stage: WasmlineLoadStage = WasmlineLoadStage.SOURCE_RESOLUTION,
        code: WasmlineErrorCode = WasmlineErrorCode.SOURCE_RESOLUTION_FAILED,
    ): WasmlineSourceResolution.Complete = structuredResolutionFailure(stage, code, cause)

    private class ArtifactLockEntry(val mutex: Mutex = Mutex(), var users: Int = 0)

    private val sha256Pattern = Regex("^[0-9a-fA-F]{64}$")
}
