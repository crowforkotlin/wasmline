package crow.wasmline.plugin.core.aot

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.loader.model.WasmlineManifestProtocol
import java.io.File
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies deterministic and traversal-safe content-addressed artifact storage.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
class WasmlineContentAddressedStoreTest {
    @Test
    fun storesAndDeduplicatesByDigestAndFormat() = withStoreDirectory { root ->
        val packageDirectory = File(root, "package")
        val firstSource = File(root, "first.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val secondSource = File(root, "second.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val store = WasmlineContentAddressedStore(packageDirectory)

        val first = store.put(firstSource, WasmlineArtifactFormat.PWASM)
        val second = store.put(secondSource, WasmlineArtifactFormat.PWASM)

        assertEquals(first, second)
        assertEquals(
            WasmlineManifestProtocol.artifactRelativePath(first.sha256, WasmlineArtifactFormat.PWASM),
            first.relativePath,
        )
        assertTrue(first.file.isFile)
        assertEquals(3L, first.sizeBytes)
    }

    @Test
    fun rejectsPathsThatEscapePackageDirectory() = withStoreDirectory { root ->
        val store = WasmlineContentAddressedStore(File(root, "package"))

        assertFailsWith<IllegalArgumentException> { store.resolve("../outside.cwasm") }
    }

    @Test
    fun deduplicatesConcurrentWritesToTheSameContentPath() = withStoreDirectory { root ->
        val packageDirectory = File(root, "package")
        val sources = List(8) { index ->
            File(root, "source-$index.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        }
        val store = WasmlineContentAddressedStore(packageDirectory)
        val executor = Executors.newFixedThreadPool(sources.size)

        try {
            val stored = sources
                .map { source -> executor.submit<StoredWasmlineArtifact> { store.put(source, WasmlineArtifactFormat.CWASM) } }
                .map { future -> future.get() }

            assertEquals(1, stored.map(StoredWasmlineArtifact::relativePath).distinct().size)
            assertTrue(stored.first().file.isFile)
        } finally {
            executor.shutdownNow()
        }
    }
}

private inline fun withStoreDirectory(block: (File) -> Unit) {
    val directory = createTempDirectory("wasmline-content-store-test").toFile()
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}
