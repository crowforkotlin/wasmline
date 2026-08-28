package crow.wasmline.plugin.core.aot

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.loader.model.WasmlineManifestProtocol
import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import crow.wasmline.plugin.core.toolchain.FileDigest
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores package artifacts under deterministic SHA-256 paths.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
class WasmlineContentAddressedStore(private val packageDirectory: File) {
    private val contentLocks = ConcurrentHashMap<String, Any>()

    /** Copies one verified file into the package content store and returns its identity. */
    fun put(source: File, format: WasmlineArtifactFormat): StoredWasmlineArtifact {
        require(source.isFile && source.length() > 0) { "Artifact source does not exist or is empty: ${source.absolutePath}" }
        val digest = FileDigest.sha256Hex(source)
        val size = source.length()
        val relativePath = WasmlineManifestProtocol.artifactRelativePath(digest, format)
        return synchronized(contentLocks.computeIfAbsent(relativePath) { Any() }) {
            val destination = resolve(relativePath)
            if (destination.isFile) {
                require(destination.length() == size && FileDigest.sha256Hex(destination) == digest) {
                    "Existing content-addressed artifact does not match its path: ${destination.absolutePath}"
                }
                return@synchronized StoredWasmlineArtifact(destination, relativePath, digest, size)
            }

            val parent = destination.parentFile
            check(parent.isDirectory || parent.mkdirs()) {
                "Unable to create artifact content directory: ${parent.absolutePath}"
            }
            val temporary = Files.createTempFile(parent.toPath(), ".artifact-", ".tmp").toFile()
            try {
                source.inputStream().use { input -> temporary.outputStream().use(input::copyTo) }
                require(temporary.length() == size && FileDigest.sha256Hex(temporary) == digest) {
                    "Staged content-addressed artifact failed integrity verification."
                }
                runCatching {
                    Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }.getOrElse {
                    Files.move(temporary.toPath(), destination.toPath())
                }
            } finally {
                temporary.delete()
            }
            StoredWasmlineArtifact(destination, relativePath, digest, size)
        }
    }

    /** Resolves a package-relative artifact path without allowing traversal. */
    fun resolve(relativePath: String): File {
        val root = packageDirectory.toPath().toAbsolutePath().normalize()
        val resolved = root.resolve(relativePath).normalize()
        require(resolved.startsWith(root)) { "Artifact content path escapes its package directory." }
        return resolved.toFile()
    }
}

/**
 * Identifies one file already committed to a package content store.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
data class StoredWasmlineArtifact(val file: File, val relativePath: String, val sha256: String, val sizeBytes: Long)
