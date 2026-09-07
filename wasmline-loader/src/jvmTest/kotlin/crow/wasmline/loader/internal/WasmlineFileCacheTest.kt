package crow.wasmline.loader.internal

import crow.wasmline.loader.network.WasmlineHttpResponse
import crow.wasmline.loader.network.WasmlineHttpStatus
import crow.wasmline.loader.network.WasmlineNetworkClient
import crow.wasmline.loader.network.WasmlineNetworkSink
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WasmlineFileCacheTest {
    @Test
    fun `artifact download streams to one atomically published file`() = runTest {
        withTemporaryCache { directory, cache ->
            val bytes = ByteArray(192 * 1024) { index -> (index % 251).toByte() }
            var fetchCalled = false
            var streamedChunks = 0
            val client = object : WasmlineNetworkClient {
                override suspend fun fetch(url: String): WasmlineHttpResponse {
                    fetchCalled = true
                    error("Artifact download must not use whole-response fetch")
                }

                override suspend fun fetchTo(url: String, sink: WasmlineNetworkSink): WasmlineHttpStatus {
                    var offset = 0
                    while (offset < bytes.size) {
                        val byteCount = minOf(16 * 1024, bytes.size - offset)
                        sink.write(bytes, offset = offset, byteCount = byteCount)
                        offset += byteCount
                        streamedChunks++
                    }
                    return WasmlineHttpStatus(200)
                }
            }

            val resolution = cache.resolveArtifact(
                key = "artifact_${bytes.sha256()}",
                extension = ".cwasm",
                expectedSha256 = bytes.sha256(),
                expectedSizeBytes = bytes.size.toLong(),
                maxArtifactBytes = bytes.size.toLong(),
                networkClient = client,
                artifactUrl = "https://example.com/plugin.cwasm",
            )

            val ready = assertIs<ArtifactFileResolution.Ready>(resolution)
            assertFalse(ready.cacheHit)
            assertFalse(fetchCalled)
            assertTrue(streamedChunks > 1)
            assertContentEquals(bytes, File(ready.path).readBytes())
            assertEquals(1, directory.listFiles().orEmpty().count { it.isFile })
            assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith(".tmp-") })
        }
    }

    @Test
    fun `sha mismatch never publishes the artifact`() = runTest {
        withTemporaryCache { directory, cache ->
            val expected = "expected".encodeToByteArray()
            val downloaded = "corrupt".encodeToByteArray()
            val client = streamingClient(downloaded)

            val resolution = cache.resolveArtifact(
                key = "artifact_${expected.sha256()}",
                extension = ".pwasm",
                expectedSha256 = expected.sha256(),
                expectedSizeBytes = downloaded.size.toLong(),
                maxArtifactBytes = downloaded.size.toLong(),
                networkClient = client,
                artifactUrl = "https://example.com/plugin.pwasm",
            )

            assertIs<ArtifactFileResolution.HashMismatch>(resolution)
            assertFalse(directory.listFiles().orEmpty().any { it.isFile })
        }
    }

    @Test
    fun `verified cache hit does not access the network`() = runTest {
        withTemporaryCache { _, cache ->
            val bytes = "cached artifact".encodeToByteArray()
            val key = "artifact_${bytes.sha256()}"
            val first = cache.resolveArtifact(
                key = key,
                extension = ".cwasm",
                expectedSha256 = bytes.sha256(),
                expectedSizeBytes = bytes.size.toLong(),
                maxArtifactBytes = bytes.size.toLong(),
                networkClient = streamingClient(bytes),
                artifactUrl = "https://example.com/plugin.cwasm",
            )
            val firstReady = assertIs<ArtifactFileResolution.Ready>(first)

            val second = cache.resolveArtifact(
                key = key,
                extension = ".cwasm",
                expectedSha256 = bytes.sha256(),
                expectedSizeBytes = bytes.size.toLong(),
                maxArtifactBytes = bytes.size.toLong(),
                networkClient = null,
                artifactUrl = "https://example.com/plugin.cwasm",
            )

            val secondReady = assertIs<ArtifactFileResolution.Ready>(second)
            assertTrue(secondReady.cacheHit)
            assertEquals(firstReady.path, secondReady.path)
        }
    }

    @Test
    fun `capacity cleanup evicts an older unprotected entry`() {
        val directory = Files.createTempDirectory("wasmline-file-cache").toFile()
        try {
            val cache = WasmlineFileCache(
                cacheDirectory = directory.absolutePath,
                maxCacheBytes = 12,
                minimumEvictionAgeMs = 0,
            )

            cache.put("old", ByteArray(8) { 1 })
            cache.put("new", ByteArray(8) { 2 })

            assertFalse(cache.exists("old"))
            assertTrue(cache.exists("new"))
            assertEquals(8L, directory.listFiles().orEmpty().sumOf(File::length))
        } finally {
            directory.deleteRecursively()
        }
    }

    private suspend fun withTemporaryCache(block: suspend (File, WasmlineFileCache) -> Unit) {
        val directory = Files.createTempDirectory("wasmline-file-cache").toFile()
        try {
            block(
                directory,
                WasmlineFileCache(
                    cacheDirectory = directory.absolutePath,
                    maxCacheBytes = 1024L * 1024L,
                    minimumEvictionAgeMs = 0,
                ),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun streamingClient(bytes: ByteArray): WasmlineNetworkClient = object : WasmlineNetworkClient {
        override suspend fun fetch(url: String): WasmlineHttpResponse = error("Unexpected whole-response fetch")

        override suspend fun fetchTo(url: String, sink: WasmlineNetworkSink): WasmlineHttpStatus {
            sink.write(bytes, offset = 0, byteCount = bytes.size)
            return WasmlineHttpStatus(200)
        }
    }

    private fun ByteArray.sha256(): String = toByteString().sha256().hex()
}
