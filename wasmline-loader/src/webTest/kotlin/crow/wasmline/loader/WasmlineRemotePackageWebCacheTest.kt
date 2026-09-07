@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package crow.wasmline.loader

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.WasmlineLoadState
import crow.wasmline.WasmlineWeb
import crow.wasmline.extensions.Keys
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.loader.internal.WasmlineHostArtifactTarget
import crow.wasmline.loader.internal.WasmlineRemotePackageResolution
import crow.wasmline.loader.internal.crypto.Ed25519
import crow.wasmline.loader.model.SignedManifestEnvelope
import crow.wasmline.loader.model.WasmlineArtifactTarget
import crow.wasmline.loader.model.WasmlineArtifactVariant
import crow.wasmline.loader.model.WasmlineManifest
import crow.wasmline.loader.model.WasmlineManifestProtocol
import crow.wasmline.loader.model.WasmlineManifestWireFormat
import crow.wasmline.loader.model.WasmlineRuntimeContract
import crow.wasmline.loader.network.WasmlineHttpResponse
import crow.wasmline.loader.network.WasmlineHttpStatus
import crow.wasmline.loader.network.WasmlineNetworkClient
import crow.wasmline.loader.network.WasmlineNetworkSink
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies Loader-managed remote artifact caching on Web hosts.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
class WasmlineRemotePackageWebCacheTest {
    private val privateKey = Keys.PRIVATE_KEY_1.decodeHex()
    private val publicKey = Keys.PUBLIC_KEY_1.decodeHex()

    @Test
    fun reusesVerifiedBytesAndDownloadsAgainAfterInvalidation() = runTest {
        val manifestUrl = "https://example.com/web-cache/manifest.wlm"
        val artifactBytes = byteArrayOf(1, 2, 3, 4)
        val digest = artifactBytes.toByteString().sha256().hex()
        val artifactUrl = artifactUrl(manifestUrl, digest)
        val envelopeBytes = signAndEncode(manifest(digest, artifactBytes.size.toLong()))
        val requests = mutableListOf<String>()
        val client = networkClient(requests, manifestUrl, envelopeBytes, artifactUrl, artifactBytes)
        val cache = TestMemoryCache()

        try {
            assertIs<WasmlineSourceResolution.ContinueWith>(resolve(manifestUrl, client, cache))
            assertEquals(listOf(manifestUrl, artifactUrl), requests)
            assertTrue(WasmlineWeb.hasBytes(artifactUrl))

            assertIs<WasmlineSourceResolution.ContinueWith>(resolve(manifestUrl, client, cache))
            assertEquals(listOf(manifestUrl, artifactUrl), requests)

            WasmlineWeb.invalidate(artifactUrl)
            cache.remove("artifact_$digest")
            assertFalse(WasmlineWeb.hasBytes(artifactUrl))
            assertIs<WasmlineSourceResolution.ContinueWith>(resolve(manifestUrl, client, cache))
            assertEquals(listOf(manifestUrl, artifactUrl, artifactUrl), requests)
            assertTrue(WasmlineWeb.hasBytes(artifactUrl))
        } finally {
            WasmlineWeb.invalidate(artifactUrl)
        }
    }

    @Test
    fun doesNotCacheArtifactWithInvalidDigest() = runTest {
        val manifestUrl = "https://example.com/web-invalid-digest/manifest.wlm"
        val declaredBytes = byteArrayOf(1, 2, 3, 4)
        val receivedBytes = byteArrayOf(4, 3, 2, 1)
        val digest = declaredBytes.toByteString().sha256().hex()
        val artifactUrl = artifactUrl(manifestUrl, digest)
        val envelopeBytes = signAndEncode(manifest(digest, declaredBytes.size.toLong()))
        val requests = mutableListOf<String>()
        val client = networkClient(requests, manifestUrl, envelopeBytes, artifactUrl, receivedBytes)

        try {
            val resolution = resolve(manifestUrl, client, TestMemoryCache())

            val complete = assertIs<WasmlineSourceResolution.Complete>(resolution)
            val failure = assertIs<WasmlineLoadState.Failure>(complete.state)
            assertEquals(WasmlineErrorCode.ARTIFACT_INTEGRITY_FAILED, failure.failure.code)
            assertFalse(WasmlineWeb.hasBytes(artifactUrl))
            assertEquals(listOf(manifestUrl, artifactUrl), requests)
        } finally {
            WasmlineWeb.invalidate(artifactUrl)
        }
    }

    @Test
    fun doesNotCacheArtifactWithInvalidSize() = runTest {
        val manifestUrl = "https://example.com/web-invalid-size/manifest.wlm"
        val artifactBytes = byteArrayOf(1, 2, 3, 4)
        val digest = artifactBytes.toByteString().sha256().hex()
        val artifactUrl = artifactUrl(manifestUrl, digest)
        val envelopeBytes = signAndEncode(manifest(digest, artifactBytes.size.toLong() + 1))
        val requests = mutableListOf<String>()
        val client = networkClient(requests, manifestUrl, envelopeBytes, artifactUrl, artifactBytes)

        try {
            val resolution = resolve(manifestUrl, client, TestMemoryCache())

            val complete = assertIs<WasmlineSourceResolution.Complete>(resolution)
            val failure = assertIs<WasmlineLoadState.Failure>(complete.state)
            assertEquals(WasmlineErrorCode.ARTIFACT_INTEGRITY_FAILED, failure.failure.code)
            assertFalse(WasmlineWeb.hasBytes(artifactUrl))
            assertEquals(listOf(manifestUrl, artifactUrl), requests)
        } finally {
            WasmlineWeb.invalidate(artifactUrl)
        }
    }

    private suspend fun resolve(
        manifestUrl: String,
        networkClient: WasmlineNetworkClient,
        cache: WasmlineCache,
    ): WasmlineSourceResolution {
        val source = WasmlineSource.RemoteManifestUrl(manifestUrl)
        return WasmlineRemotePackageResolution.resolve(
            source = source,
            request = WasmlineLoadRequest(
                source = source,
                options = WasmlineLoadOptions(
                    networkClient = networkClient,
                    trustedKeys = trustedKeys(),
                    cache = cache,
                ),
            ),
            host = browserHost(),
        )
    }

    private fun networkClient(
        requests: MutableList<String>,
        manifestUrl: String,
        manifestBytes: ByteArray,
        artifactUrl: String,
        artifactBytes: ByteArray,
    ): WasmlineNetworkClient = object : WasmlineNetworkClient {
        override suspend fun fetch(url: String): WasmlineHttpResponse = error("Remote package data must use fetchTo.")

        override suspend fun fetchTo(url: String, sink: WasmlineNetworkSink): WasmlineHttpStatus {
            requests += url
            val bytes = when (url) {
                manifestUrl -> manifestBytes
                artifactUrl -> artifactBytes
                else -> return WasmlineHttpStatus(404)
            }
            sink.write(bytes, 0, bytes.size)
            return WasmlineHttpStatus(200)
        }
    }

    private fun manifest(digest: String, sizeBytes: Long): WasmlineManifest = WasmlineManifest(
        pluginId = "crow.wasmline.web-cache",
        version = "12.3.4",
        versionCode = 1,
        minSdkVersion = "12.3.4",
        buildTimestamp = 0,
        runtimeContract = WasmlineRuntimeContract(
            executionModel = WasmlineExecutionModel.CORE_WASM,
            invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
        ),
        artifactTargets = listOf(
            WasmlineArtifactTarget(
                format = WasmlineArtifactFormat.RAW_WASM,
                architecture = "wasm32",
                pointerWidth = 32,
                variants = listOf(WasmlineArtifactVariant(sha256 = digest, sizeBytes = sizeBytes)),
            ),
        ),
    )

    private fun signAndEncode(manifest: WasmlineManifest): ByteArray {
        val canonical = WasmlineManifestProtocol.canonicalize(manifest)
        val payload = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), canonical)
        val formatVersion = WasmlineManifestWireFormat.CURRENT_FORMAT_VERSION
        val envelope = SignedManifestEnvelope(
            signature = Ed25519.sign(
                WasmlineManifestProtocol.signingMessage(formatVersion, payload).toByteString(),
                privateKey,
            ).toByteArray(),
            formatVersion = formatVersion,
            payload = payload,
        )
        return ProtoBuf.encodeToByteArray(SignedManifestEnvelope.serializer(), envelope)
    }

    private fun trustedKeys(): WasmlineTrustedKeySet = WasmlineTrustedKeySet.Builder()
        .add("Ed25519", null, publicKey.toByteArray())
        .build()

    private fun browserHost(): WasmlineHostArtifactTarget = WasmlineHostArtifactTarget(
        operatingSystem = "browser",
        architecture = "wasm32",
        pointerWidth = 32,
        supportedArtifactFormats = setOf(WasmlineArtifactFormat.RAW_WASM),
    )

    private fun artifactUrl(manifestUrl: String, digest: String): String = manifestUrl.substringBeforeLast('/') + "/" +
        WasmlineManifestProtocol.artifactRelativePath(digest, WasmlineArtifactFormat.RAW_WASM)
}

/**
 * Stores manifest bytes in memory for Web cache behavior tests.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
private class TestMemoryCache : WasmlineCache {
    private val entries = mutableMapOf<String, ByteArray>()

    override fun get(key: String): ByteArray? = entries[key]?.copyOf()

    override fun put(key: String, bytes: ByteArray) {
        entries[key] = bytes.copyOf()
    }

    override fun exists(key: String): Boolean = key in entries

    /** Removes one test cache entry. */
    fun remove(key: String) {
        entries.remove(key)
    }
}
