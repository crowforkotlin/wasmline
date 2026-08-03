@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline.loader

import crow.wasmline.WasmlineCache
import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineLoadState
import crow.wasmline.WasmlineNoOpCache
import crow.wasmline.WasmlineTrustedKeySet
import crow.wasmline.extensions.Keys
import crow.wasmline.loader.internal.crypto.Ed25519
import crow.wasmline.loader.model.SignedManifestEnvelope
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineArtifactType
import crow.wasmline.loader.model.WasmlineManifest
import crow.wasmline.network.WasmlineHttpResponse
import crow.wasmline.network.WasmlineNetworkClient
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `remote source without networkClient returns failure`() {
        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.RemoteManifestUrl("https://example.com/plugin.wlm"),
            ),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertTrue(failure.cause.contains("request.resolvers.remotePackage or request.config.networkClient"))
    }

    @Test
    fun `custom resolver takes priority over networkClient`() {
        val customCalled = booleanArrayOf(false)
        val fakeNetworkClient = WasmlineNetworkClient { _ ->
            throw AssertionError("Network client should not be called when custom resolver is provided")
        }

        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.RemoteManifestUrl("https://example.com/plugin.wlm"),
                config = WasmlineConfig(networkClient = fakeNetworkClient),
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
    fun `networkClient auto-delegates when no custom resolver`() {
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
        val artifactBytes = fakeArtifactBytes()

        val networkClient = WasmlineNetworkClient { url ->
            when {
                url.endsWith(".wlm") || url.endsWith("/manifest.wlm") ->
                    WasmlineHttpResponse(200, envelopeBytes)

                url.endsWith("lib.pwasm") ->
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
                config = WasmlineConfig(
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
    fun `manifest fetch failure returns descriptive error`() {
        val networkClient = WasmlineNetworkClient { _ ->
            WasmlineHttpResponse(500, ByteArray(0))
        }

        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.RemoteManifestUrl("https://example.com/plugin.wlm"),
                config = WasmlineConfig(
                    networkClient = networkClient,
                    cache = WasmlineNoOpCache,
                ),
            ),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertTrue(failure.cause.contains("Failed to fetch manifest"))
    }

    @Test
    fun `invalid manifest protobuf returns parse error`() {
        val networkClient = WasmlineNetworkClient { _ ->
            WasmlineHttpResponse(200, "not valid protobuf".encodeToByteArray())
        }

        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.RemoteManifestUrl("https://example.com/plugin.wlm"),
                config = WasmlineConfig(
                    networkClient = networkClient,
                    cache = WasmlineNoOpCache,
                ),
            ),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertTrue(failure.cause.contains("Failed to parse manifest"))
    }

    @Test
    fun `signature verification fails with wrong key`() {
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
                config = WasmlineConfig(
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
    fun `signature verification skipped when trustedKeys is null`() {
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
        val artifactBytes = fakeArtifactBytes()

        val networkClient = WasmlineNetworkClient { url ->
            when {
                url.endsWith(".wlm") -> WasmlineHttpResponse(200, envelopeBytes)
                url.endsWith("lib.pwasm") -> WasmlineHttpResponse(200, artifactBytes)
                else -> WasmlineHttpResponse(404, ByteArray(0))
            }
        }

        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.RemoteManifestUrl("https://example.com/plugin.wlm"),
                config = WasmlineConfig(
                    networkClient = networkClient,
                    trustedKeys = null,
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
    fun `artifact SHA256 mismatch returns descriptive error`() {
        val manifest = createTestManifest(
            artifacts = listOf(
                WasmlineArtifact(
                    type = WasmlineArtifactType.PWASM,
                    url = "lib.pwasm",
                    sha256 = "wrong_sha256_that_wont_match",
                    is64Bit = true,
                ),
            ),
        )
        val envelopeBytes = signAndEncodeEnvelope(manifest)

        val networkClient = WasmlineNetworkClient { url ->
            when {
                url.endsWith(".wlm") -> WasmlineHttpResponse(200, envelopeBytes)
                url.endsWith("lib.pwasm") -> WasmlineHttpResponse(200, fakeArtifactBytes())
                else -> WasmlineHttpResponse(404, ByteArray(0))
            }
        }

        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.RemoteManifestUrl("https://example.com/plugin.wlm"),
                config = WasmlineConfig(
                    networkClient = networkClient,
                    cache = WasmlineNoOpCache,
                ),
            ),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertTrue(failure.cause.contains("sha256 verification"))
    }

    @Test
    fun `cache hit avoids network call for manifest`() {
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
        val artifactBytes = fakeArtifactBytes()

        val cache = InMemoryCache()
        val manifestUrl = "https://example.com/plugin.wlm"
        val manifestHash = crow.wasmline.loader.internal.Djb2.hashToHex8(manifestUrl.encodeToByteArray())
        val manifestCacheKey = "m_$manifestHash"
        cache.put(manifestCacheKey, envelopeBytes)

        var fetchCount = 0
        val networkClient = WasmlineNetworkClient { url ->
            fetchCount++
            when {
                url.endsWith(".wlm") -> WasmlineHttpResponse(200, envelopeBytes)
                url.endsWith("lib.pwasm") -> WasmlineHttpResponse(200, artifactBytes)
                else -> WasmlineHttpResponse(404, ByteArray(0))
            }
        }

        DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.RemoteManifestUrl(manifestUrl),
                config = WasmlineConfig(
                    networkClient = networkClient,
                    cache = cache,
                ),
            ),
        )

        assertEquals(1, fetchCount, "Only artifact fetch expected, manifest should come from cache")
    }
}

private class InMemoryCache : WasmlineCache {
    private val store = mutableMapOf<String, ByteArray>()

    override fun get(key: String): ByteArray? = store[key]
    override fun put(key: String, bytes: ByteArray) {
        store[key] = bytes
    }
    override fun exists(key: String): Boolean = store.containsKey(key)
}
