@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline.loader

import crow.wasmline.WasmlineLoadState
import crow.wasmline.extensions.Keys
import crow.wasmline.loader.internal.crypto.Ed25519
import crow.wasmline.loader.internal.currentHostArtifactTarget
import crow.wasmline.loader.model.SignedManifestEnvelope
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineArtifactType
import crow.wasmline.loader.model.WasmlineManifest
import crow.wasmline.loader.network.WasmlineHttpResponse
import crow.wasmline.loader.network.WasmlineHttpStatus
import crow.wasmline.loader.network.WasmlineNetworkClient
import crow.wasmline.loader.network.WasmlineNetworkSink
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Verifies remote manifest resolution, caching, compatibility, and signatures. */
class WasmlineRemotePackageResolutionTest {

    private val privateKey = Keys.PRIVATE_KEY_1.decodeHex()
    private val publicKey = Keys.PUBLIC_KEY_1.decodeHex()

    private fun createTestManifest(artifacts: List<WasmlineArtifact>): WasmlineManifest = WasmlineManifest(
        pluginId = "crow.wasmline.test",
        version = "1.0.0",
        versionCode = 1,
        minSdkVersion = "0.9.0",
        buildTimestamp = Clock.System.now().toEpochMilliseconds(),
        artifacts = artifacts,
    )

    private fun signAndEncodeEnvelope(manifest: WasmlineManifest, publicKeyId: String? = null): ByteArray {
        val manifestBytes = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), manifest)
        val signature = Ed25519.sign(manifestBytes.toByteString(), privateKey)
        val envelope = SignedManifestEnvelope(
            signature = signature.toByteArray(),
            manifest = manifest,
            algorithm = "Ed25519",
            publicKeyId = publicKeyId,
        )
        return ProtoBuf.encodeToByteArray(SignedManifestEnvelope.serializer(), envelope)
    }

    private fun fakeArtifactBytes(): ByteArray = "fake compiled wasm artifact content for testing".encodeToByteArray()

    private fun fakeArtifactSha256(): String = fakeArtifactBytes().toByteString().sha256().hex()

    private fun trustedKeys(): WasmlineTrustedKeySet = WasmlineTrustedKeySet.Builder()
        .add("Ed25519", keyId = null, publicKey = publicKey.toByteArray())
        .build()

    private fun compatibleCraneliftArtifact(url: String, sha256: String): WasmlineArtifact {
        val target = currentHostArtifactTarget
        return WasmlineArtifact(
            type = WasmlineArtifactType.CWASM,
            url = url,
            sha256 = sha256,
            targetCpu = target.cpu,
            targetOs = target.os,
            targetCompilerVersion = "wasmtime-${requireNotNull(target.wasmtimeVersion)}",
            is64Bit = target.is64Bit,
        )
    }

    @Test
    fun `remote source without networkClient returns failure`() = runTest {
        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.RemoteManifestUrl("https://example.com/plugin.wlm"),
            ),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertTrue(failure.cause.contains("request.options.networkClient or request.resolvers.remotePackage"))
    }

    @Test
    fun `custom resolver takes priority over networkClient`() = runTest {
        val customCalled = booleanArrayOf(false)
        val fakeNetworkClient = WasmlineNetworkClient { _ ->
            throw AssertionError("Network client should not be called when custom resolver is provided")
        }

        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.RemoteManifestUrl("https://example.com/plugin.wlm"),
                options = WasmlineLoadOptions(networkClient = fakeNetworkClient),
                resolvers = WasmlineSourceResolvers(
                    remotePackage = WasmlineRemotePackageResolver { _, _ ->
                        customCalled[0] = true
                        WasmlineSourceResolution.Complete(
                            WasmlineLoadState.Failure(
                                code = WasmlineLoadState.CODE_FAILURE,
                                cause = "custom resolver was called",
                            ),
                        )
                    },
                ),
            ),
        )

        assertTrue(customCalled[0], "Custom resolver should have been called")
        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertEquals("custom resolver was called", failure.cause)
    }

    @Test
    fun `networkClient auto-delegates when no custom resolver`() = runTest {
        val manifest = createTestManifest(
            artifacts = listOf(
                compatibleCraneliftArtifact(
                    url = "lib.cwasm",
                    sha256 = fakeArtifactSha256(),
                ),
            ),
        )
        val envelopeBytes = signAndEncodeEnvelope(manifest)
        val artifactBytes = fakeArtifactBytes()

        val networkClient = WasmlineNetworkClient { url ->
            when {
                url.endsWith(".wlm") || url.endsWith("/manifest.wlm") ->
                    WasmlineHttpResponse(200, envelopeBytes)

                url.endsWith("lib.cwasm") ->
                    WasmlineHttpResponse(200, artifactBytes)

                else ->
                    WasmlineHttpResponse(404, ByteArray(0))
            }
        }

        val trustedKeys = WasmlineTrustedKeySet.Builder()
            .add("Ed25519", keyId = null, publicKey = publicKey.toByteArray())
            .build()

        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.RemoteManifestUrl("https://example.com/plugin.wlm"),
                options = WasmlineLoadOptions(
                    networkClient = networkClient,
                    trustedKeys = trustedKeys,
                    cache = WasmlineNoOpCache,
                ),
            ),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertTrue(
            failure.cause.contains("Load failure") ||
                failure.cause.contains("not found") ||
                failure.cause.contains("artifact path"),
            "Expected runtime load error, got: ${failure.cause}",
        )
    }

    @Test
    fun `built-in artifact download uses streaming network path`() = runTest {
        val artifactBytes = "streamed artifact content".encodeToByteArray()
        val manifest = createTestManifest(
            artifacts = listOf(
                compatibleCraneliftArtifact(
                    url = "streamed.cwasm",
                    sha256 = artifactBytes.toByteString().sha256().hex(),
                ),
            ),
        )
        val envelopeBytes = signAndEncodeEnvelope(manifest)
        var wholeArtifactFetchCalled = false
        var streamingArtifactFetchCalled = false
        val networkClient = object : WasmlineNetworkClient {
            override suspend fun fetch(url: String): WasmlineHttpResponse = when {
                url.endsWith(".wlm") -> WasmlineHttpResponse(200, envelopeBytes)

                url.endsWith("streamed.cwasm") -> {
                    wholeArtifactFetchCalled = true
                    error("Artifact must use fetchTo")
                }

                else -> WasmlineHttpResponse(404, ByteArray(0))
            }

            override suspend fun fetchTo(url: String, sink: WasmlineNetworkSink): WasmlineHttpStatus {
                streamingArtifactFetchCalled = true
                sink.write(artifactBytes, offset = 0, byteCount = artifactBytes.size)
                return WasmlineHttpStatus(200)
            }
        }

        DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.RemoteManifestUrl("https://example.com/streamed.wlm"),
                options = WasmlineLoadOptions(
                    networkClient = networkClient,
                    trustedKeys = trustedKeys(),
                    cache = WasmlineNoOpCache,
                ),
            ),
        )

        assertFalse(wholeArtifactFetchCalled)
        assertTrue(streamingArtifactFetchCalled)
    }

    @Test
    fun `manifest fetch failure returns descriptive error`() = runTest {
        val networkClient = WasmlineNetworkClient { _ ->
            WasmlineHttpResponse(500, ByteArray(0))
        }

        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.RemoteManifestUrl("https://example.com/plugin.wlm"),
                options = WasmlineLoadOptions(
                    networkClient = networkClient,
                    cache = WasmlineNoOpCache,
                ),
            ),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertTrue(failure.cause.contains("Failed to fetch manifest"))
    }

    @Test
    fun `invalid manifest protobuf returns parse error`() = runTest {
        val networkClient = WasmlineNetworkClient { _ ->
            WasmlineHttpResponse(200, "not valid protobuf".encodeToByteArray())
        }

        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.RemoteManifestUrl("https://example.com/plugin.wlm"),
                options = WasmlineLoadOptions(
                    networkClient = networkClient,
                    cache = WasmlineNoOpCache,
                ),
            ),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertTrue(failure.cause.contains("Failed to parse manifest"))
    }

    @Test
    fun `signature verification fails with wrong key`() = runTest {
        val manifest = createTestManifest(
            artifacts = listOf(
                WasmlineArtifact(
                    type = WasmlineArtifactType.PWASM,
                    url = "lib.pwasm",
                    sha256 = fakeArtifactSha256(),
                    is64Bit = true,
                ),
            ),
        )
        val envelopeBytes = signAndEncodeEnvelope(manifest)

        val networkClient = WasmlineNetworkClient { _ ->
            WasmlineHttpResponse(200, envelopeBytes)
        }

        val wrongKey = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".decodeHex()
        val trustedKeys = WasmlineTrustedKeySet.Builder()
            .add("Ed25519", keyId = null, publicKey = wrongKey.toByteArray())
            .build()

        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.RemoteManifestUrl("https://example.com/plugin.wlm"),
                options = WasmlineLoadOptions(
                    networkClient = networkClient,
                    trustedKeys = trustedKeys,
                    cache = WasmlineNoOpCache,
                ),
            ),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertTrue(failure.cause.contains("signature verification failed"))
    }

    @Test
    fun `remote package rejects a missing trusted key source`() = runTest {
        val manifest = createTestManifest(
            artifacts = listOf(
                WasmlineArtifact(
                    type = WasmlineArtifactType.PWASM,
                    url = "lib.pwasm",
                    sha256 = fakeArtifactSha256(),
                    is64Bit = true,
                ),
            ),
        )
        val envelopeBytes = signAndEncodeEnvelope(manifest)
        val networkClient = WasmlineNetworkClient { url ->
            when {
                url.endsWith(".wlm") -> WasmlineHttpResponse(200, envelopeBytes)
                else -> WasmlineHttpResponse(404, ByteArray(0))
            }
        }

        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.RemoteManifestUrl("https://example.com/plugin.wlm"),
                options = WasmlineLoadOptions(
                    networkClient = networkClient,
                    trustedKeys = null,
                    cache = WasmlineNoOpCache,
                ),
            ),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertTrue(failure.cause.contains("requires trustedKeys"))
    }

    @Test
    fun `artifact SHA256 mismatch returns descriptive error`() = runTest {
        val manifest = createTestManifest(
            artifacts = listOf(
                compatibleCraneliftArtifact(
                    url = "lib.cwasm",
                    sha256 = "0".repeat(64),
                ),
            ),
        )
        val envelopeBytes = signAndEncodeEnvelope(manifest)

        val networkClient = WasmlineNetworkClient { url ->
            when {
                url.endsWith(".wlm") -> WasmlineHttpResponse(200, envelopeBytes)
                url.endsWith("lib.cwasm") -> WasmlineHttpResponse(200, fakeArtifactBytes())
                else -> WasmlineHttpResponse(404, ByteArray(0))
            }
        }

        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.RemoteManifestUrl("https://example.com/plugin.wlm"),
                options = WasmlineLoadOptions(
                    networkClient = networkClient,
                    trustedKeys = trustedKeys(),
                    cache = WasmlineNoOpCache,
                ),
            ),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertTrue(failure.cause.contains("sha256 verification"))
    }

    @Test
    fun `cache hit avoids network call for manifest`() = runTest {
        val manifest = createTestManifest(
            artifacts = listOf(
                compatibleCraneliftArtifact(
                    url = "lib.cwasm",
                    sha256 = fakeArtifactSha256(),
                ),
            ),
        )
        val envelopeBytes = signAndEncodeEnvelope(manifest)
        val artifactBytes = fakeArtifactBytes()

        val cache = InMemoryCache()
        val manifestUrl = "https://example.com/plugin.wlm"
        val manifestCacheKey = manifestCacheKey(manifestUrl)
        cache.put(manifestCacheKey, envelopeBytes)
        cache.put("$manifestCacheKey.timestamp", Clock.System.now().toEpochMilliseconds().toString().encodeToByteArray())

        var fetchCount = 0
        val networkClient = WasmlineNetworkClient { url ->
            fetchCount++
            when {
                url.endsWith(".wlm") -> WasmlineHttpResponse(200, envelopeBytes)
                url.endsWith("lib.cwasm") -> WasmlineHttpResponse(200, artifactBytes)
                else -> WasmlineHttpResponse(404, ByteArray(0))
            }
        }

        DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.RemoteManifestUrl(manifestUrl),
                options = WasmlineLoadOptions(
                    networkClient = networkClient,
                    trustedKeys = trustedKeys(),
                    cache = cache,
                ),
            ),
        )

        assertEquals(1, fetchCount, "Only artifact fetch expected, manifest should come from cache")
    }

    @Test
    fun `fresh manifest and artifact cache load without networkClient`() = runTest {
        val artifactBytes = fakeArtifactBytes()
        val artifactSha256 = fakeArtifactSha256()
        val manifest = createTestManifest(
            artifacts = listOf(compatibleCraneliftArtifact(url = "lib.cwasm", sha256 = artifactSha256)),
        )
        val manifestUrl = "https://example.com/offline-plugin.wlm"
        val manifestCacheKey = manifestCacheKey(manifestUrl)
        val cache = InMemoryCache().apply {
            put(manifestCacheKey, signAndEncodeEnvelope(manifest))
            put(
                "$manifestCacheKey.timestamp",
                Clock.System.now().toEpochMilliseconds().toString().encodeToByteArray(),
            )
            put("artifact_$artifactSha256", artifactBytes)
        }

        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.RemoteManifestUrl(manifestUrl),
                options = WasmlineLoadOptions(
                    trustedKeys = trustedKeys(),
                    cache = cache,
                ),
            ),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertFalse(failure.cause.contains("networkClient"), failure.cause)
        assertFalse(failure.cause.contains("not available in cache"), failure.cause)
    }

    private fun manifestCacheKey(url: String): String = "manifest_${url.encodeToByteArray().toByteString().sha256().hex()}"
}

private class InMemoryCache : WasmlineCache {
    private val store = mutableMapOf<String, ByteArray>()

    override fun get(key: String): ByteArray? = store[key]
    override fun put(key: String, bytes: ByteArray) {
        store[key] = bytes
    }
    override fun exists(key: String): Boolean = store.containsKey(key)
}
