package crow.wasmline.plugin.core.packaging

import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packages a manifest and compiled artifacts into a plugin archive.
 *
 * Date: 2026-07-31
 * Author: crowforkotlin
 */

@InternalWasmlineToolingApi
object PluginPackager {
    /** Creates the plugin archive at the given destination. */
    fun createZip(
        manifestFile: File,
        artifacts: List<WasmlineArtifact>,
        artifactDirectory: File,
        destination: File,
        folderPrefix: String,
    ): File {
        destination.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(destination)).use { archive ->
            addFile(archive, manifestFile, "$folderPrefix/${manifestFile.name}")
            artifacts.forEach { artifact ->
                val artifactFile = File(artifactDirectory, artifact.url)
                if (artifactFile.isFile) addFile(archive, artifactFile, "$folderPrefix/${artifactFile.name}")
            }
        }
        return destination
    }

    private fun addFile(archive: ZipOutputStream, file: File, entryName: String) {
        archive.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(archive) }
        archive.closeEntry()
    }
}
