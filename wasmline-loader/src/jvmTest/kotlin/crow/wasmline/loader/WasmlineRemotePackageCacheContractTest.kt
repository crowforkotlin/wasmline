@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package crow.wasmline.loader

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.extensions.Keys
import crow.wasmline.loader.internal.WasmlineHostArtifactTarget
import crow.wasmline.loader.internal.WasmlineRemotePackageResolution
import crow.wasmline.loader.internal.crypto.Ed25519
import crow.wasmline.loader.internal.defaultCacheDirectory
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
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies cache ownership and URL derivation for signed remote packages.
 *
 * Date: 2026-11-11
 * Author: crowforkotlin
 */
class WasmlineRemotePackageCacheContractTest {
    private val privateKey = Keys.PRIVATE_KEY_1.decodeHex()
    private val publicKey = Keys.PUBLIC_KEY_1.decodeHex()

    @Test
    fun noOpCacheLeavesNoArtifactInTheBuiltInCacheDirectory() = runTest {
        val artifactBytes = byteArrayOf(4, 5, 6)
        val digest = artifactBytes.toByteString().sha256().hex()
        val envelopeBytes = signAndEncode(rawManifest(digest, artifactBytes.size.toLong()))

        val cacheDirectory = builtInCacheDirectory()
        val resolution = resolve(
            manifestUrl = MANIFEST_URL,
            cache = WasmlineNoOpCache,
            networkClient = clientFor(envelopeBytes, artifactBytes, digest),
        )

        val continuation = assertIs<WasmlineSourceResolution.ContinueWith>(resolution)
        val artifact = assertIs<WasmlineSource.LocalArtifactPath>(continuation.source)
        assertContentEquals(artifactBytes, File(artifact.path).readBytes())
        assertFalse(
            File(artifact.path).toPath().toAbsolutePath().normalize()
                .startsWith(cacheDirectory.toPath().toAbsolutePath().normalize()),
            "NoOp cache must not materialize the artifact under ${cacheDirectory.absolutePath}",
        )
    }

    @Test
    fun customCacheReceivesTheDownloadedArtifact() = runTest {
        val artifactBytes = byteArrayOf(7, 8, 9)
        val digest = artifactBytes.toByteString().sha256().hex()
        val envelopeBytes = signAndEncode(rawManifest(digest, artifactBytes.size.toLong()))
        val store = mutableMapOf<String, ByteArray>()
        val cache = object : WasmlineCache {
            override fun get(key: String): ByteArray? = store[key]?.copyOf()

            override fun put(key: String, bytes: ByteArray) {
                store[key] = bytes.copyOf()
            }
        }

        val cacheDirectory = builtInCacheDirectory()
        val resolution = resolve(
            manifestUrl = MANIFEST_URL,
            cache = cache,
            networkClient = clientFor(envelopeBytes, artifactBytes, digest),
        )

        val continuation = assertIs<WasmlineSourceResolution.ContinueWith>(resolution)
        val staged = assertIs<WasmlineSource.LocalArtifactPath>(continuation.source)
        assertContentEquals(artifactBytes, store["artifact_$digest"])
        assertFalse(
            File(staged.path).toPath().toAbsolutePath().normalize()
                .startsWith(cacheDirectory.toPath().toAbsolutePath().normalize()),
            "A custom cache must not fall back to ${cacheDirectory.absolutePath}",
        )
    }

    @Test
    fun manifestUrlQueryIsPreservedForManifestAndArtifact() = runTest {
        val artifactBytes = byteArrayOf(1, 1, 1)
        val digest = artifactBytes.toByteString().sha256().hex()
        val envelopeBytes = signAndEncode(rawManifest(digest, artifactBytes.size.toLong()))
        val requested = mutableListOf<String>()

        val resolution = resolve(
            manifestUrl = "$MANIFEST_URL?channel=stable",
            cache = WasmlineNoOpCache,
            networkClient = object : WasmlineNetworkClient {
                override suspend fun fetch(url: String): WasmlineHttpResponse = error("Remote packages stream with fetchTo.")

                override suspend fun fetchTo(url: String, sink: WasmlineNetworkSink): WasmlineHttpStatus {
                    requested += url
                    return when {
                        url == "$MANIFEST_URL?channel=stable" -> {
                            sink.write(envelopeBytes, 0, envelopeBytes.size)
                            WasmlineHttpStatus(200)
                        }

                        url.endsWith("$digest.wasm?channel=stable") -> {
                            sink.write(artifactBytes, 0, artifactBytes.size)
                            WasmlineHttpStatus(200)
                        }

                        else -> WasmlineHttpStatus(404)
                    }
                }
            },
        )

        val continuation = assertIs<WasmlineSourceResolution.ContinueWith>(resolution)
        val artifact = assertIs<WasmlineSource.LocalArtifactPath>(continuation.source)
        assertEquals("$MANIFEST_URL?channel=stable", requested.first())
        assertTrue(
            requested[1].endsWith(".wasm?channel=stable"),
            "Artifact request must keep the query string, got ${requested[1]}",
        )
        assertTrue(File(artifact.path).isFile)
    }

    private fun clientFor(envelopeBytes: ByteArray, artifactBytes: ByteArray, digest: String): WasmlineNetworkClient =
        object : WasmlineNetworkClient {
            override suspend fun fetch(url: String): WasmlineHttpResponse = error("Remote packages stream with fetchTo.")

            override suspend fun fetchTo(url: String, sink: WasmlineNetworkSink): WasmlineHttpStatus = when {
                url.endsWith("/manifest.wlm") -> {
                    sink.write(envelopeBytes, 0, envelopeBytes.size)
                    WasmlineHttpStatus(200)
                }

                url.endsWith("/$digest.wasm") -> {
                    sink.write(artifactBytes, 0, artifactBytes.size)
                    WasmlineHttpStatus(200)
                }

                else -> WasmlineHttpStatus(404)
            }
        }

    private suspend fun resolve(
        manifestUrl: String,
        cache: WasmlineCache,
        networkClient: WasmlineNetworkClient,
    ): WasmlineSourceResolution {
        val source = WasmlineSource.RemoteManifestUrl(manifestUrl)
        return WasmlineRemotePackageResolution.resolve(
            source = source,
            request = WasmlineLoadRequest(
                source = source,
                options = WasmlineLoadOptions(
                    networkClient = networkClient,
                    trustedKeys = WasmlineTrustedKeySet.Builder().add("Ed25519", null, publicKey.toByteArray()).build(),
                    cache = cache,
                ),
            ),
            host = browserHost(),
        )
    }

    private fun rawManifest(digest: String, sizeBytes: Long): WasmlineManifest = WasmlineManifestProtocol.canonicalize(
        WasmlineManifest(
            pluginId = "crow.wasmline.cache",
            version = "1.0.0",
            versionCode = 1,
            buildTimestamp = 0,
            runtimeContract = WasmlineRuntimeContract(
                WasmlineExecutionModel.CORE_WASM,
                WasmlineInvocationProtocol.RAW_EXPORT,
            ),
            artifactTargets = listOf(
                WasmlineArtifactTarget(
                    format = WasmlineArtifactFormat.RAW_WASM,
                    architecture = "wasm32",
                    pointerWidth = 32,
                    variants = listOf(WasmlineArtifactVariant(sha256 = digest, sizeBytes = sizeBytes)),
                ),
            ),
        ),
    )

    private fun signAndEncode(manifest: WasmlineManifest): ByteArray {
        val payload = ProtoBuf.encodeToByteArray(WasmlineManifest.serializer(), manifest)
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

    private fun browserHost() = WasmlineHostArtifactTarget(
        operatingSystem = "browser",
        architecture = "wasm32",
        pointerWidth = 32,
        supportedArtifactFormats = setOf(WasmlineArtifactFormat.RAW_WASM),
    )

    /**
     * Returns the loader's built-in cache directory for the current host.
     *
     * The tests assert that staged artifacts live outside this directory rather
     * than mutating the process environment.
     */
    private fun builtInCacheDirectory(): File = File(requireNotNull(defaultCacheDirectory()))

    private companion object {
        const val MANIFEST_URL = "https://example.com/plugin/manifest.wlm"
    }
}
