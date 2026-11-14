package crow.wasmline.loader.internal

import crow.wasmline.loader.network.WasmlineHttpResponse
import crow.wasmline.loader.network.WasmlineHttpStatus
import crow.wasmline.loader.network.WasmlineNetworkClient
import crow.wasmline.loader.network.WasmlineNetworkSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

            assertNull(cache.get("old"))
            assertNotNull(cache.get("new"))
            assertEquals(8L, directory.listFiles().orEmpty().sumOf(File::length))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `bounds a burst of entries written inside the eviction grace window`() {
        val directory = Files.createTempDirectory("wasmline-cache-burst").toFile()
        try {
            val cache = WasmlineFileCache(cacheDirectory = directory.absolutePath, maxCacheBytes = 1024L)
            repeat(40) { cache.put("artifact_$it", ByteArray(100)) }
            val total = directory.listFiles().orEmpty().sumOf(File::length)

            assertTrue(total <= 1024L, "cache holds $total bytes with maxCacheBytes=1024")
            assertTrue(directory.listFiles().orEmpty().size < 40, "entries below the grace window were not evicted")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `refuses a single entry larger than the limit`() {
        val directory = Files.createTempDirectory("wasmline-cache-oversize").toFile()
        try {
            val cache = WasmlineFileCache(cacheDirectory = directory.absolutePath, maxCacheBytes = 1024L)
            cache.put("artifact_huge", ByteArray(8 * 1024 * 1024))
            val total = directory.listFiles().orEmpty().sumOf(File::length)

            assertEquals(0L, total)
            assertNull(cache.get("artifact_huge"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `removes a cached artifact that exceeds the limit before using it`() = runTest {
        val directory = Files.createTempDirectory("wasmline-cache-stale-oversize").toFile()
        try {
            val cache = WasmlineFileCache(cacheDirectory = directory.absolutePath, maxCacheBytes = 1024L)
            val oversized = ByteArray(8 * 1024 * 1024) { 7 }
            File(directory, "artifact_oversized.wasm").writeBytes(oversized)
            val sha256 = oversized.toByteString().sha256().hex()

            val resolution = cache.resolveArtifact(
                key = "artifact_oversized",
                extension = ".wasm",
                expectedSha256 = sha256,
                expectedSizeBytes = oversized.size.toLong(),
                maxArtifactBytes = 16L * 1024L * 1024L,
                networkClient = null,
                artifactUrl = "https://example.invalid/oversized.wasm",
            )

            assertIs<ArtifactFileResolution.Missing>(resolution)
            assertFalse(File(directory, "artifact_oversized.wasm").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `concurrent artifact downloads share capacity across cache instances`() = runTest {
        val directory = Files.createTempDirectory("wasmline-cache-concurrent").toFile()
        val paths = mutableListOf<String>()
        try {
            val firstCache = WasmlineFileCache(directory.absolutePath, maxCacheBytes = 8)
            val secondCache = WasmlineFileCache(directory.absolutePath, maxCacheBytes = 8)
            val firstStarted = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val firstBytes = byteArrayOf(1, 2, 3, 4, 5, 6)
            val secondBytes = byteArrayOf(6, 5, 4, 3, 2, 1)
            val firstClient = gatedClient(firstBytes, firstStarted, secondStarted)
            val secondClient = gatedClient(secondBytes, secondStarted, firstStarted)
            val first = async(Dispatchers.Default) { resolve(firstCache, firstBytes, firstClient) }
            firstStarted.await()
            val second = async(Dispatchers.Default) { resolve(secondCache, secondBytes, secondClient) }
            val ready = listOf(first, second).awaitAll().map { assertIs<ArtifactFileResolution.Ready>(it) }
            paths += ready.map(ArtifactFileResolution.Ready::path)

            assertContentEquals(firstBytes, File(ready[0].path).readBytes())
            assertContentEquals(secondBytes, File(ready[1].path).readBytes())
            assertEquals(6L, directory.listFiles().orEmpty().sumOf(File::length))
            assertEquals(1, ready.count { File(it.path).parentFile == directory })
            assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith(".tmp-") })
        } finally {
            paths.forEach { WasmlineTransientArtifacts.deleteIfTransient(it) }
            directory.deleteRecursively()
        }
    }

    @Test
    fun `failed and cancelled downloads release capacity reservations`() = runTest {
        val directory = Files.createTempDirectory("wasmline-cache-reservation").toFile()
        try {
            val cache = WasmlineFileCache(directory.absolutePath, maxCacheBytes = 6)
            val bytes = byteArrayOf(1, 2, 3, 4, 5, 6)
            val corrupt = bytes.reversedArray()
            assertIs<ArtifactFileResolution.HashMismatch>(resolve(cache, bytes, streamingClient(corrupt)))
            val started = CompletableDeferred<Unit>()
            val neverReleased = CompletableDeferred<Unit>()
            val job = launch { resolve(cache, bytes, gatedClient(bytes, started, neverReleased)) }
            started.await()
            job.cancelAndJoin()

            val ready = assertIs<ArtifactFileResolution.Ready>(resolve(cache, bytes, streamingClient(bytes)))
            assertEquals(directory, File(ready.path).parentFile)
            assertEquals(6L, directory.listFiles().orEmpty().sumOf(File::length))
        } finally {
            directory.deleteRecursively()
        }
    }

    private suspend fun resolve(cache: WasmlineFileCache, bytes: ByteArray, client: WasmlineNetworkClient): ArtifactFileResolution =
        cache.resolveArtifact(
            "artifact_${bytes.sha256()}",
            ".cwasm",
            bytes.sha256(),
            bytes.size.toLong(),
            100,
            client,
            "https://cache.invalid/artifact.cwasm",
        )

    private fun gatedClient(
        bytes: ByteArray,
        started: CompletableDeferred<Unit>,
        proceed: CompletableDeferred<Unit>,
    ): WasmlineNetworkClient = object : WasmlineNetworkClient {
        override suspend fun fetch(url: String): WasmlineHttpResponse = error("Unexpected whole-response fetch")

        override suspend fun fetchTo(url: String, sink: WasmlineNetworkSink): WasmlineHttpStatus {
            started.complete(Unit)
            proceed.await()
            sink.write(bytes, 0, bytes.size)
            return WasmlineHttpStatus(200)
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
