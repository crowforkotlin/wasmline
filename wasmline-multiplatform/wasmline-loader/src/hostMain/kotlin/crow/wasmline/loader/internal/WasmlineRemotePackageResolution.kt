@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline.loader.internal

import crow.wasmline.WasmlineLoadState
import crow.wasmline.WasmlineLog
import crow.wasmline.loader.VerifiedPackageArtifact
import crow.wasmline.loader.WasmlineCache
import crow.wasmline.loader.WasmlineLoadRequest
import crow.wasmline.loader.WasmlineSource
import crow.wasmline.loader.WasmlineSourceResolution
import crow.wasmline.loader.model.SignedManifestEnvelope
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.network.WasmlineNetworkClient
import crow.wasmline.loader.toDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import okio.ByteString.Companion.toByteString

/** Built-in remote manifest, artifact verification, and cache pipeline. */
internal object WasmlineRemotePackageResolution {
    private const val P = "[WasmlineRemotePackageResolution]"

    suspend fun resolve(source: WasmlineSource.RemoteManifestUrl, request: WasmlineLoadRequest): WasmlineSourceResolution {
        val options = request.options
        val networkClient = options.networkClient
        val cache = options.cache ?: defaultCacheOrNull()
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
            )
        } else {
            failure("Failed to fetch manifest from '$manifestUrl'.")
        }

        val envelope = try {
            ProtoBuf.decodeFromByteArray(SignedManifestEnvelope.serializer(), manifestBytes)
        } catch (_: Exception) {
            return failure("Failed to parse manifest envelope from '$manifestUrl'.")
        }

        val manifest = when (
            val verification = WasmlinePackageSignatureVerifier.verify(
                envelope = envelope,
                trustedKeys = options.trustedKeys,
                packageLocation = manifestUrl,
            )
        ) {
            is WasmlineManifestVerification.Verified -> verification.manifest
            is WasmlineManifestVerification.Rejected -> return failure(verification.cause)
        }

        val artifact = WasmlineLocalPackageResolution.selectArtifact(manifest.artifacts)
            ?: return failure(
                "No compatible artifact found in remote package '$manifestUrl' " +
                    "for host ${describe(currentHostArtifactTarget)}.",
            )
        val pendingDescriptor = artifact.toDescriptor(path = "pending")
        pendingDescriptor.validationError()?.let {
            return failure("Invalid artifact descriptor for '${artifact.url}': $it")
        }
        if (artifact.sha256.isBlank()) {
            return failure("Artifact '${artifact.url}' from '$manifestUrl' is missing sha256 metadata.")
        }
        if (!sha256Pattern.matches(artifact.sha256)) {
            return failure("Artifact '${artifact.url}' from '$manifestUrl' has invalid sha256 metadata.")
        }

        val artifactUrl = resolveArtifactUrl(manifestUrl, artifact.url)
        val expectedSha256 = artifact.sha256.lowercase()
        val artifactCacheKey = "artifact_$expectedSha256"
        val artifactBytes = when (
            val resolution = resolveArtifactBytes(
                cache = cache,
                cacheKey = artifactCacheKey,
                networkClient = networkClient,
                artifactUrl = artifactUrl,
                expectedSha256 = expectedSha256,
            )
        ) {
            is ArtifactBytesResolution.Found -> resolution.bytes

            is ArtifactBytesResolution.HashMismatch -> return failure(
                "Artifact '${artifact.url}' from '$artifactUrl' failed sha256 verification. " +
                    "Expected $expectedSha256, actual ${resolution.actualSha256}.",
            )

            ArtifactBytesResolution.Missing -> return if (networkClient == null) {
                failure(
                    "Artifact '$artifactUrl' is not available in cache. " +
                        "Provide request.options.networkClient or request.resolvers.remotePackage.",
                )
            } else {
                failure("Failed to fetch artifact from '$artifactUrl'.")
            }
        }

        val localPath = writeCachedArtifact(artifactBytes, artifact, artifactCacheKey)
            ?: return failure("Failed to write cached artifact to local file system.")

        return WasmlineSourceResolution.ContinueWith(
            VerifiedPackageArtifact(
                descriptor = artifact.toDescriptor(localPath),
                expectedSha256 = expectedSha256,
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

    private suspend fun resolveArtifactBytes(
        cache: WasmlineCache?,
        cacheKey: String,
        networkClient: WasmlineNetworkClient?,
        artifactUrl: String,
        expectedSha256: String,
    ): ArtifactBytesResolution {
        val cached = cache?.get(cacheKey)
        if (cached != null) {
            val actualSha256 = sha256Hex(cached)
            if (actualSha256.equals(expectedSha256, ignoreCase = true)) {
                WasmlineLog.logger?.debug("$P Artifact cache hit: $cacheKey")
                return ArtifactBytesResolution.Found(cached)
            }
            WasmlineLog.logger?.warn(
                "$P Ignoring corrupt artifact cache entry $cacheKey: expected $expectedSha256, actual $actualSha256",
            )
        }

        if (networkClient == null) return ArtifactBytesResolution.Missing
        WasmlineLog.logger?.debug("$P Artifact cache miss, fetching: $artifactUrl")
        val downloaded = fetchBytes(networkClient, artifactUrl) ?: return ArtifactBytesResolution.Missing
        val actualSha256 = sha256Hex(downloaded)
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            WasmlineLog.logger?.warn(
                "$P Artifact sha256 mismatch for $artifactUrl: expected $expectedSha256, actual $actualSha256",
            )
            return ArtifactBytesResolution.HashMismatch(actualSha256)
        }
        cache?.put(cacheKey, downloaded)
        return ArtifactBytesResolution.Found(downloaded)
    }

    private sealed interface ArtifactBytesResolution {
        data class Found(val bytes: ByteArray) : ArtifactBytesResolution
        data class HashMismatch(val actualSha256: String) : ArtifactBytesResolution
        data object Missing : ArtifactBytesResolution
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

    private fun writeCachedArtifact(bytes: ByteArray, artifact: WasmlineArtifact, cacheKey: String): String? {
        val cacheDir = defaultCacheDirectory() ?: return null
        val extension = when (artifact.type) {
            crow.wasmline.loader.model.WasmlineArtifactType.WASM -> ".wasm"
            crow.wasmline.loader.model.WasmlineArtifactType.CWASM -> ".cwasm"
            crow.wasmline.loader.model.WasmlineArtifactType.PWASM -> ".pwasm"
            crow.wasmline.loader.model.WasmlineArtifactType.COMPONENT_WASM -> ".component.wasm"
        }
        val localPath = "$cacheDir/$cacheKey$extension"
        return if (writeHostFileBytes(localPath, bytes)) localPath else null
    }

    private fun defaultCacheOrNull(): WasmlineCache? {
        val directory = defaultCacheDirectory() ?: return null
        return WasmlineFileCache(cacheDirectory = directory)
    }

    private fun sha256Hex(bytes: ByteArray): String = bytes.toByteString().sha256().hex()

    private fun describe(target: WasmlineHostArtifactTarget): String {
        val bitness = if (target.is64Bit) "64-bit" else "32-bit"
        return "${target.os}/${target.cpu} ($bitness)"
    }

    private fun failure(cause: String): WasmlineSourceResolution.Complete = WasmlineSourceResolution.Complete(
        WasmlineLoadState.Failure(
            code = WasmlineLoadState.CODE_FAILURE,
            cause = cause,
        ),
    )

    private val sha256Pattern = Regex("^[0-9a-fA-F]{64}$")
}
