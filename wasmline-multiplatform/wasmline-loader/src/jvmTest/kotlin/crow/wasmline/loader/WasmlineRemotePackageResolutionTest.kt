@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline.loader

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineEngineKind
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.WasmlineLoadState
import crow.wasmline.WasmlineNativeRuntimeInfo
import crow.wasmline.extensions.Keys
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.loader.internal.WasmlineFileCache
import crow.wasmline.loader.internal.WasmlineHostArtifactTarget
import crow.wasmline.loader.internal.WasmlineRemotePackageResolution
import crow.wasmline.loader.internal.crypto.Ed25519
import crow.wasmline.loader.model.SignedManifestEnvelope
import crow.wasmline.loader.model.WasmlineAotCompatibilityProfile
import crow.wasmline.loader.model.WasmlineArtifactTarget
import crow.wasmline.loader.model.WasmlineArtifactVariant
import crow.wasmline.loader.model.WasmlineManifest
import crow.wasmline.loader.model.WasmlineManifestLimits
import crow.wasmline.loader.model.WasmlineManifestProtocol
import crow.wasmline.loader.model.WasmlineManifestWireFormat
import crow.wasmline.loader.model.WasmlineRuntimeContract
import crow.wasmline.loader.network.WasmlineHttpResponse
import crow.wasmline.loader.network.WasmlineHttpStatus
import crow.wasmline.loader.network.WasmlineNetworkClient
import crow.wasmline.loader.network.WasmlineNetworkSink
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies remote manifest caching and single-artifact streaming resolution.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
class WasmlineRemotePackageResolutionTest {
    private val privateKey = Keys.PRIVATE_KEY_1.decodeHex()
    private val publicKey = Keys.PUBLIC_KEY_1.decodeHex()

    @Test
    fun coldLoadRequestsManifestAndOneContentAddressedArtifact() = runTest {
        withRemoteCache { cache ->
            val artifactBytes = byteArrayOf(1, 2, 3)
            val digest = artifactBytes.toByteString().sha256().hex()
            val envelopeBytes = signAndEncode(rawManifest(digest, artifactBytes.size.toLong()))
            val expectedArtifactUrl = "https://example.com/plugin/" +
                WasmlineManifestProtocol.artifactRelativePath(digest, WasmlineArtifactFormat.RAW_WASM)
            val requested = mutableListOf<String>()
            var wholeArtifactFetchCalled = false
            val networkClient = object : WasmlineNetworkClient {
                override suspend fun fetch(url: String): WasmlineHttpResponse {
                    wholeArtifactFetchCalled = true
                    error("Remote package data must use fetchTo.")
                }

                override suspend fun fetchTo(url: String, sink: WasmlineNetworkSink): WasmlineHttpStatus {
                    requested += url
                    return when (url) {
                        MANIFEST_URL -> {
                            sink.write(envelopeBytes, 0, envelopeBytes.size)
                            WasmlineHttpStatus(200)
                        }

                        expectedArtifactUrl -> {
                            sink.write(artifactBytes, 0, artifactBytes.size)
                            WasmlineHttpStatus(200)
                        }

                        else -> WasmlineHttpStatus(404)
                    }
                }
            }

            val resolution = resolve(
                manifestUrl = MANIFEST_URL,
                networkClient = networkClient,
                cache = cache,
                trustedKeys = trustedKeys(),
                host = browserHost(),
            )

            val continuation = assertIs<WasmlineSourceResolution.ContinueWith>(resolution)
            val artifact = assertIs<VerifiedPackageArtifact>(continuation.source)
            assertEquals(WasmlineArtifactFormat.RAW_WASM, artifact.descriptor.artifactFormat)
            assertEquals(listOf(MANIFEST_URL, expectedArtifactUrl), requested)
            assertFalse(wholeArtifactFetchCalled)
        }
    }

    @Test
    fun selectedCwasmIntegrityFailureDoesNotFallBackToPulley() = runTest {
        withRemoteCache { cache ->
            val manifest = nativeManifest()
            val cwasmPath = WasmlineManifestProtocol.artifactRelativePath(CWASM_DIGEST, WasmlineArtifactFormat.CWASM)
            val pwasmPath = WasmlineManifestProtocol.artifactRelativePath(PWASM_DIGEST, WasmlineArtifactFormat.PWASM)
            val requestedArtifacts = mutableListOf<String>()
            val envelopeBytes = signAndEncode(manifest)
            val networkClient = object : WasmlineNetworkClient {
                override suspend fun fetch(url: String): WasmlineHttpResponse = error("Remote package data must use fetchTo.")

                override suspend fun fetchTo(url: String, sink: WasmlineNetworkSink): WasmlineHttpStatus {
                    if (url == MANIFEST_URL) {
                        sink.write(envelopeBytes, 0, envelopeBytes.size)
                        return WasmlineHttpStatus(200)
                    }
                    requestedArtifacts += url
                    assertTrue(url.endsWith(cwasmPath))
                    assertFalse(url.endsWith(pwasmPath))
                    sink.write(byteArrayOf(9, 9, 9), 0, 3)
                    return WasmlineHttpStatus(200)
                }
            }

            val resolution = resolve(
                MANIFEST_URL,
                networkClient,
                cache,
                trustedKeys(),
                nativeHost(),
            )

            val complete = assertIs<WasmlineSourceResolution.Complete>(resolution)
            val failure = assertIs<WasmlineLoadState.Failure>(complete.state)
            assertEquals(WasmlineErrorCode.ARTIFACT_INTEGRITY_FAILED, failure.failure.code)
            assertEquals(1, requestedArtifacts.size)
        }
    }

    @Test
    fun cachedManifestIsReverifiedWhenTrustPolicyChanges() = runTest {
        withRemoteCache { cache ->
            val artifactBytes = byteArrayOf(1, 2, 3)
            val digest = artifactBytes.toByteString().sha256().hex()
            val envelopeBytes = signAndEncode(rawManifest(digest, 3))
            var manifestFetches = 0
            val networkClient = object : WasmlineNetworkClient {
                override suspend fun fetch(url: String): WasmlineHttpResponse = error("Remote package data must use fetchTo.")

                override suspend fun fetchTo(url: String, sink: WasmlineNetworkSink): WasmlineHttpStatus {
                    val bytes = if (url == MANIFEST_URL) {
                        manifestFetches++
                        envelopeBytes
                    } else {
                        artifactBytes
                    }
                    sink.write(bytes, 0, bytes.size)
                    return WasmlineHttpStatus(200)
                }
            }
            assertIs<WasmlineSourceResolution.ContinueWith>(
                resolve(MANIFEST_URL, networkClient, cache, trustedKeys(), browserHost()),
            )

            val wrongKeys = WasmlineTrustedKeySet.Builder()
                .add("Ed25519", null, "a".repeat(64).decodeHex().toByteArray())
                .build()
            val second = resolve(MANIFEST_URL, networkClient, cache, wrongKeys, browserHost())

            val complete = assertIs<WasmlineSourceResolution.Complete>(second)
            val failure = assertIs<WasmlineLoadState.Failure>(complete.state)
            assertEquals(WasmlineErrorCode.SIGNATURE_VERIFICATION_FAILED, failure.failure.code)
            assertEquals(1, manifestFetches)
        }
    }

    @Test
    fun customByteCacheCanResolveACompletePackageOffline() = runTest {
        val artifactBytes = byteArrayOf(1, 2, 3)
        val digest = artifactBytes.toByteString().sha256().hex()
        val envelopeBytes = signAndEncode(rawManifest(digest, artifactBytes.size.toLong()))
        val manifestCacheKey = "manifest_${MANIFEST_URL.encodeToByteArray().toByteString().sha256().hex()}"
        val entries = mutableMapOf(
            manifestCacheKey to envelopeBytes,
            "$manifestCacheKey.timestamp" to System.currentTimeMillis().toString().encodeToByteArray(),
            "artifact_$digest" to artifactBytes,
        )
        val cache = object : WasmlineCache {
            override fun get(key: String): ByteArray? = entries[key]?.copyOf()

            override fun put(key: String, bytes: ByteArray) {
                entries[key] = bytes.copyOf()
            }

            override fun exists(key: String): Boolean = key in entries
        }
        val source = WasmlineSource.RemoteManifestUrl(MANIFEST_URL)

        val resolution = WasmlineRemotePackageResolution.resolve(
            source,
            WasmlineLoadRequest(
                source,
                options = WasmlineLoadOptions(
                    trustedKeys = trustedKeys(),
                    cache = cache,
                ),
            ),
            browserHost(),
        )

        val continuation = assertIs<WasmlineSourceResolution.ContinueWith>(resolution)
        val artifact = assertIs<VerifiedPackageArtifact>(continuation.source)
        assertTrue(File(artifact.descriptor.path).isFile)
        assertEquals(WasmlineArtifactFormat.RAW_WASM, artifact.descriptor.artifactFormat)
    }

    @Test
    fun rejectsSelectedArtifactAboveConfiguredLimitBeforeDownload() = runTest {
        withRemoteCache { cache ->
            val envelopeBytes = signAndEncode(rawManifest(RAW_DIGEST, 4))
            var artifactDownloadCalled = false
            val networkClient = object : WasmlineNetworkClient {
                override suspend fun fetch(url: String): WasmlineHttpResponse = error("Remote package data must use fetchTo.")

                override suspend fun fetchTo(url: String, sink: WasmlineNetworkSink): WasmlineHttpStatus {
                    if (url == MANIFEST_URL) {
                        sink.write(envelopeBytes, 0, envelopeBytes.size)
                        return WasmlineHttpStatus(200)
                    }
                    artifactDownloadCalled = true
                    return WasmlineHttpStatus(200)
                }
            }
            val source = WasmlineSource.RemoteManifestUrl(MANIFEST_URL)
            val resolution = WasmlineRemotePackageResolution.resolve(
                source,
                WasmlineLoadRequest(
                    source,
                    options = WasmlineLoadOptions(
                        networkClient = networkClient,
                        trustedKeys = trustedKeys(),
                        cache = cache,
                        maxArtifactBytes = 3,
                    ),
                ),
                browserHost(),
            )

            val complete = assertIs<WasmlineSourceResolution.Complete>(resolution)
            val failure = assertIs<WasmlineLoadState.Failure>(complete.state)
            assertEquals(WasmlineErrorCode.ARTIFACT_INTEGRITY_FAILED, failure.failure.code)
            assertFalse(artifactDownloadCalled)
        }
    }

    @Test
    fun rejectsStreamedManifestAboveConfiguredLimit() = runTest {
        withRemoteCache { cache ->
            val oversizedManifest = ByteArray(5) { 1 }
            var requests = 0
            val networkClient = object : WasmlineNetworkClient {
                override suspend fun fetch(url: String): WasmlineHttpResponse = error("Remote package data must use fetchTo.")

                override suspend fun fetchTo(url: String, sink: WasmlineNetworkSink): WasmlineHttpStatus {
                    requests++
                    sink.write(oversizedManifest, 0, oversizedManifest.size)
                    return WasmlineHttpStatus(200)
                }
            }
            val source = WasmlineSource.RemoteManifestUrl(MANIFEST_URL)
            val resolution = WasmlineRemotePackageResolution.resolve(
                source,
                WasmlineLoadRequest(
                    source,
                    options = WasmlineLoadOptions(
                        networkClient = networkClient,
                        trustedKeys = trustedKeys(),
                        cache = cache,
                        manifestLimits = WasmlineManifestLimits(maxManifestBytes = 4, maxPayloadBytes = 4),
                    ),
                ),
                browserHost(),
            )

            val complete = assertIs<WasmlineSourceResolution.Complete>(resolution)
            val failure = assertIs<WasmlineLoadState.Failure>(complete.state)
            assertEquals(WasmlineErrorCode.MANIFEST_INVALID, failure.failure.code)
            assertEquals(1, requests)
        }
    }

    private suspend fun resolve(
        manifestUrl: String,
        networkClient: WasmlineNetworkClient,
        cache: WasmlineFileCache,
        trustedKeys: WasmlineTrustedKeys,
        host: WasmlineHostArtifactTarget,
    ): WasmlineSourceResolution {
        val source = WasmlineSource.RemoteManifestUrl(manifestUrl)
        return WasmlineRemotePackageResolution.resolve(
            source = source,
            request = WasmlineLoadRequest(
                source = source,
                options = WasmlineLoadOptions(
                    networkClient = networkClient,
                    trustedKeys = trustedKeys,
                    cache = cache,
                ),
            ),
            host = host,
        )
    }

    private fun rawManifest(digest: String, sizeBytes: Long): WasmlineManifest = WasmlineManifest(
        pluginId = "crow.wasmline.remote",
        version = "12.3.4",
        versionCode = 1,
        minSdkVersion = "12.3.4",
        buildTimestamp = 0,
        runtimeContract = contract(),
        artifactTargets = listOf(
            WasmlineArtifactTarget(
                format = WasmlineArtifactFormat.RAW_WASM,
                architecture = "wasm32",
                pointerWidth = 32,
                variants = listOf(WasmlineArtifactVariant(sha256 = digest, sizeBytes = sizeBytes)),
            ),
        ),
    )

    private fun nativeManifest(): WasmlineManifest = WasmlineManifestProtocol.canonicalize(
        WasmlineManifest(
            pluginId = "crow.wasmline.remote",
            version = "12.3.4",
            versionCode = 1,
            minSdkVersion = "12.3.4",
            buildTimestamp = 0,
            runtimeContract = contract(),
            aotCompatibilityProfiles = listOf(
                profile(CRANELIFT_PROFILE_ID, WasmlineEngineKind.CRANELIFT),
                profile(PULLEY_PROFILE_ID, WasmlineEngineKind.PULLEY),
            ),
            artifactTargets = listOf(
                WasmlineArtifactTarget(
                    format = WasmlineArtifactFormat.CWASM,
                    operatingSystem = "linux",
                    architecture = "x86_64",
                    pointerWidth = 64,
                    cpuFeatureProfile = "baseline-v1",
                    variants = listOf(WasmlineArtifactVariant(listOf(CRANELIFT_PROFILE_ID), CWASM_DIGEST, 3)),
                ),
                WasmlineArtifactTarget(
                    format = WasmlineArtifactFormat.PWASM,
                    architecture = "pulley64",
                    pointerWidth = 64,
                    variants = listOf(WasmlineArtifactVariant(listOf(PULLEY_PROFILE_ID), PWASM_DIGEST, 3)),
                ),
            ),
        ),
    )

    private fun profile(id: String, backend: WasmlineEngineKind) = WasmlineAotCompatibilityProfile(
        id = id,
        artifactBackend = backend,
        wasmtimeVersion = "12.3.4",
        wasmtimeDistributionVersion = "12.3.4.1",
        compileProfileSchemaVersion = 1,
    )

    private fun contract() = WasmlineRuntimeContract(
        WasmlineExecutionModel.CORE_WASM,
        WasmlineInvocationProtocol.WASMLINE_SERVICE,
    )

    private fun signAndEncode(manifest: WasmlineManifest): ByteArray {
        val canonical = WasmlineManifestProtocol.canonicalize(manifest)
        val payload = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), canonical)
        val version = WasmlineManifestWireFormat.CURRENT_FORMAT_VERSION
        val envelope = SignedManifestEnvelope(
            signature = Ed25519.sign(
                WasmlineManifestProtocol.signingMessage(version, payload).toByteString(),
                privateKey,
            ).toByteArray(),
            formatVersion = version,
            payload = payload,
        )
        return ProtoBuf.encodeToByteArray(SignedManifestEnvelope.serializer(), envelope)
    }

    private fun trustedKeys(): WasmlineTrustedKeySet = WasmlineTrustedKeySet.Builder()
        .add("Ed25519", null, publicKey.toByteArray())
        .build()

    private fun browserHost() = WasmlineHostArtifactTarget(
        operatingSystem = "browser",
        architecture = "wasm32",
        pointerWidth = 32,
        supportedArtifactFormats = setOf(WasmlineArtifactFormat.RAW_WASM),
    )

    private fun nativeHost(): WasmlineHostArtifactTarget {
        val formats = setOf(WasmlineArtifactFormat.CWASM, WasmlineArtifactFormat.PWASM)
        val runtime = WasmlineNativeRuntimeInfo(
            backend = WasmlineEngineKind.CRANELIFT,
            supportedArtifactFormats = formats,
            wasmtimeVersion = "12.3.4",
            aotCompatibilityProfileIdsByBackend = mapOf(
                WasmlineEngineKind.CRANELIFT to setOf(CRANELIFT_PROFILE_ID),
                WasmlineEngineKind.PULLEY to setOf(PULLEY_PROFILE_ID),
            ),
            nativeBridgeAbiVersion = 1,
            wasmlineReleaseVersion = "1.0.0",
            operatingSystem = "linux",
            architecture = "x86_64",
            pointerWidth = 64,
            supportedCpuFeatureProfiles = setOf("baseline-v1"),
        )
        return WasmlineHostArtifactTarget(
            operatingSystem = "linux",
            architecture = "x86_64",
            pointerWidth = 64,
            supportedArtifactFormats = formats,
            nativeRuntimeInfo = runtime,
        )
    }

    /**
     * Defines remote package fixture locations and content identities.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    private companion object {
        const val MANIFEST_URL = "https://example.com/plugin/manifest.wlm"
        const val RAW_DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val CWASM_DIGEST = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val PWASM_DIGEST = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val CRANELIFT_PROFILE_ID = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
        const val PULLEY_PROFILE_ID = "sha256:2222222222222222222222222222222222222222222222222222222222222222"
    }
}

private suspend fun withRemoteCache(block: suspend (WasmlineFileCache) -> Unit) {
    val directory = createTempDirectory("wasmline-remote-cache-test").toFile()
    try {
        block(WasmlineFileCache(File(directory, "cache").absolutePath, maxCacheBytes = 1024 * 1024))
    } finally {
        directory.deleteRecursively()
    }
}
