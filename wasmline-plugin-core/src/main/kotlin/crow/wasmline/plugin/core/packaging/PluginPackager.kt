package crow.wasmline.plugin.core.packaging

import crow.wasmline.loader.model.WasmlineManifestProtocol
import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import crow.wasmline.plugin.core.aot.WasmlineAotBuildRecord
import crow.wasmline.plugin.core.aot.WasmlineAotBuildRecords
import crow.wasmline.plugin.core.manifest.ManifestSigner
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Creates a complete offline package with the canonical content-addressed layout.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
object PluginPackager {
    /** Creates a deterministic ZIP containing one manifest, its artifacts, and debug records. */
    fun createZip(
        manifestFile: File,
        buildRecord: WasmlineAotBuildRecord,
        packageDirectory: File,
        destination: File,
        folderPrefix: String,
    ): File {
        require(manifestFile.isFile && manifestFile.parentFile.canonicalFile == packageDirectory.canonicalFile) {
            "Manifest must be a direct child of the package directory."
        }
        require(folderPrefix.isNotBlank() && !folderPrefix.contains('/') && !folderPrefix.contains('\\')) {
            "ZIP folder prefix must be one path segment."
        }
        val relativePaths = buildList {
            add(manifestFile.name)
            buildRecord.compiledOutputs.forEach { output ->
                val expected = WasmlineManifestProtocol.artifactRelativePath(output.sha256, output.format)
                require(output.contentRelativePath == expected) {
                    "AOT build record contains a non-standard content path '${output.contentRelativePath}'."
                }
                add(expected)
            }
            add("debug/${ManifestSigner.MANIFEST_JSON_NAME}")
            add("debug/${WasmlineAotBuildRecords.FILE_NAME}")
            add("debug/${ManifestSigner.ARTIFACT_INDEX_JSON_NAME}")
        }.distinct().sorted()

        destination.parentFile?.let { parent -> check(parent.isDirectory || parent.mkdirs()) }
        ZipOutputStream(FileOutputStream(destination)).use { archive ->
            relativePaths.forEach { relativePath ->
                val file = resolvePackageFile(packageDirectory, relativePath)
                require(file.isFile) { "Package file does not exist: ${file.absolutePath}" }
                addFile(archive, file, "$folderPrefix/$relativePath")
            }
        }
        return destination
    }

    private fun resolvePackageFile(packageDirectory: File, relativePath: String): File {
        val root = packageDirectory.canonicalFile.toPath()
        val resolved = root.resolve(relativePath).normalize()
        require(resolved.startsWith(root)) { "Package ZIP path escapes its package directory." }
        return resolved.toFile()
    }

    private fun addFile(archive: ZipOutputStream, file: File, entryName: String) {
        val entry = ZipEntry(entryName).apply { time = 0L }
        archive.putNextEntry(entry)
        file.inputStream().use { it.copyTo(archive) }
        archive.closeEntry()
    }
}
