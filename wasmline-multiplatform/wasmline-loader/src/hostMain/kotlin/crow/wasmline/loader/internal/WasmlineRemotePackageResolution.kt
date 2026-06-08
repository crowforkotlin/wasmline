@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline.loader.internal

import crow.wasmline.WasmlineCache
import crow.wasmline.WasmlineLoadState
import crow.wasmline.WasmlineNetworkClient
import crow.wasmline.WasmlineNoOpCache
import crow.wasmline.WasmlineTrustedKeys
import crow.wasmline.loader.WasmlineLoadRequest
import crow.wasmline.loader.WasmlineSource
import crow.wasmline.loader.WasmlineSourceResolution
import crow.wasmline.loader.internal.crypto.SignatureAlgorithmId
import crow.wasmline.loader.model.SignedManifestEnvelope
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineManifest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import okio.ByteString.Companion.toByteString

/**
 * Built-in remote package resolution pipeline.
 *
 * Downloads a signed manifest from a remote URL, verifies its signature,
 * selects a compatible artifact for the current host, downloads and verifies
 * the artifact, caches it locally, and hands off to the runtime loader.
 */
internal object WasmlineRemotePackageResolution {

    fun resolve(
        source: WasmlineSource.RemoteManifestUrl,
        request: WasmlineLoadRequest,
    ): WasmlineSourceResolution {
        val networkClient = request.config.networkClient
            ?: return failure("No network client configured for remote package '${source.url}'. Provide request.config.networkClient.")

        val cache = request.config.cache ?: defaultCacheOrNull()

        // Step 1: Determine manifest URL
        val manifestUrl = resolveManifestUrl(source.url)

        // Step 2: Check cache for manifest
        val manifestCacheKey = "manifest_${sha256Hex(bytes = manifestUrl.encodeToByteArray())}"
        val manifestBytes = cache?.get(manifestCacheKey) ?: fetchBytes(
            networkClient = networkClient,
            url = manifestUrl,
            description = "manifest",
        ) ?: return failure("Failed to fetch manifest from '$manifestUrl'.")

        // Step 3: Cache manifest if freshly downloaded
        if (cache != null && !cache.exists(manifestCacheKey)) {
            cache.put(manifestCacheKey, manifestBytes)
        }

        // Step 4: Parse manifest envelope
        val envelope = try {
            ProtoBuf.decodeFromByteArray(SignedManifestEnvelope.serializer(), manifestBytes)
        } catch (_: Exception) {
            return failure("Failed to parse manifest envelope from '$manifestUrl'.")
        }

        // Step 5: Verify signature (if trustedKeys provided)
        val signatureResult = verifySignatureIfNeeded(envelope, request.config.trustedKeys, manifestUrl)
        if (signatureResult != null) return signatureResult

        // Step 6: Select compatible artifact
        val artifact = WasmlineLocalPackageResolution.selectArtifact(envelope.manifest.artifacts)
            ?: return failure(
                "No compatible artifact found in remote package '$manifestUrl' " +
                    "for host ${describe(currentHostArtifactTarget)}.",
            )

        // Step 7: Resolve artifact URL
        val artifactUrl = resolveArtifactUrl(manifestUrl, artifact.url)

        // Step 8: Check cache for artifact
        val artifactCacheKey = "artifact_${artifact.sha256}"
        val cachedArtifact = cache?.get(artifactCacheKey)

        val artifactBytes = if (cachedArtifact != null) {
            cachedArtifact
        } else {
            // Step 9: Fetch artifact
            val downloaded = fetchBytes(
                networkClient = networkClient,
                url = artifactUrl,
                description = "artifact '${artifact.url}'",
            ) ?: return failure("Failed to fetch artifact from '$artifactUrl'.")

            // Step 10: Cache artifact
            cache?.put(artifactCacheKey, downloaded)
            downloaded
        }

        // Step 11: Verify SHA256
        val actualSha256 = artifactBytes.toByteString().sha256().hex()
        if (!actualSha256.equals(artifact.sha256, ignoreCase = true)) {
            // Invalidate bad cache entry
            return failure(
                "Artifact '${artifact.url}' from '$artifactUrl' failed sha256 verification. " +
                    "Expected ${artifact.sha256}, actual $actualSha256.",
            )
        }

        // Step 12: Write artifact to local file
        val localPath = writeCachedArtifact(artifactBytes, artifact, artifactCacheKey)
            ?: return failure("Failed to write cached artifact to local file system.")

        return WasmlineSourceResolution.ContinueWith(
            WasmlineSource.LocalArtifactPath(path = localPath),
        )
    }

    private fun resolveManifestUrl(sourceUrl: String): String {
        val url = sourceUrl.trim()
        return if (url.endsWith(".wlm")) {
            url
        } else {
            url.trimEnd('/') + "/$MANIFEST_FILE_NAME"
        }
    }

    private fun fetchBytes(
        networkClient: WasmlineNetworkClient,
        url: String,
        description: String,
    ): ByteArray? {
        return try {
            val response = networkClient.fetch(url)
            if (!response.isSuccess) return null
            response.bytes
        } catch (_: Exception) {
            null
        }
    }

    private fun verifySignatureIfNeeded(
        envelope: SignedManifestEnvelope,
        trustedKeys: WasmlineTrustedKeys?,
        manifestUrl: String,
    ): WasmlineSourceResolution.Complete? {
        // No trusted keys = permissive mode (skip verification)
        if (trustedKeys == null) return null

        val algorithmId = try {
            SignatureAlgorithmId.valueOf(envelope.algorithm)
        } catch (_: IllegalArgumentException) {
            return failure("Unknown signature algorithm '${envelope.algorithm}' in manifest from '$manifestUrl'.")
        }

        val publicKey = trustedKeys.getPublicKey(envelope.algorithm, envelope.publicKeyId)
            ?: return failure(
                "No trusted key found for algorithm='${envelope.algorithm}', " +
                    "keyId='${envelope.publicKeyId}' in manifest from '$manifestUrl'.",
            )

        val algorithm = algorithmId.get()
        val manifestBytes = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), envelope.manifest)
        val verified = algorithm.verify(
            message = manifestBytes.toByteString(),
            signature = envelope.signature.toByteString(),
            publicKey = publicKey.toByteString(),
        )

        return if (verified) {
            null
        } else {
            failure("Manifest signature verification failed for '$manifestUrl'.")
        }
    }

    private fun resolveArtifactUrl(manifestUrl: String, artifactUrl: String): String {
        // Absolute URL: use as-is
        if (artifactUrl.startsWith("http://") || artifactUrl.startsWith("https://")) {
            return artifactUrl
        }
        // Relative URL: resolve against manifest base URL
        val baseUrl = manifestUrl.substringBeforeLast('/', missingDelimiterValue = "")
        return if (baseUrl.isEmpty()) {
            artifactUrl
        } else {
            "$baseUrl/$artifactUrl"
        }
    }

    private fun writeCachedArtifact(
        bytes: ByteArray,
        artifact: WasmlineArtifact,
        cacheKey: String,
    ): String? {
        val cacheDir = defaultCacheDirectory() ?: return null
        val extension = when (artifact.type) {
            crow.wasmline.loader.model.WasmlineArtifactType.WASM -> ".wasm"
            crow.wasmline.loader.model.WasmlineArtifactType.CWASM -> ".cwasm"
            crow.wasmline.loader.model.WasmlineArtifactType.PWASM -> ".pwasm"
        }
        val localPath = "$cacheDir/$cacheKey$extension"
        return if (writeHostFileBytes(localPath, bytes)) localPath else null
    }

    private fun defaultCacheOrNull(): WasmlineCache? {
        val dir = defaultCacheDirectory() ?: return null
        return WasmlineFileCache(dir)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return bytes.toByteString().sha256().hex()
    }

    private fun describe(target: WasmlineHostArtifactTarget): String {
        val bitness = if (target.is64Bit) "64-bit" else "32-bit"
        return "${target.os}/${target.cpu} ($bitness)"
    }

    private fun failure(cause: String): WasmlineSourceResolution.Complete {
        return WasmlineSourceResolution.Complete(
            WasmlineLoadState.Failure(
                code = WasmlineLoadState.CODE_FAILURE,
                cause = cause,
            ),
        )
    }
}
