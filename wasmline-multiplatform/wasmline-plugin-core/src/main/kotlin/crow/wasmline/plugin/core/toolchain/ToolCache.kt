package crow.wasmline.plugin.core.toolchain

import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import java.io.File
import java.util.Properties

/** A verified tool asset resolved from the local cache. */

@InternalWasmlineToolingApi
data class ResolvedToolAsset(val spec: ToolAssetSpec, val directory: File, val file: File)

/** Stores immutable, verified Component toolchain assets. */

@InternalWasmlineToolingApi
class ToolCache(val rootDirectory: File) {
    internal fun directoryFor(spec: ToolAssetSpec): File =
        File(rootDirectory, spec.tool.name.lowercase() + "/" + spec.version + "/" + spec.platform)

    internal fun lockFileFor(spec: ToolAssetSpec): File =
        File(rootDirectory, ".locks/" + spec.tool.name.lowercase() + "-" + spec.version + "-" + spec.platform + ".lock")

    /** Returns a cached asset only when its verification marker matches. */
    fun resolve(spec: ToolAssetSpec): ResolvedToolAsset? {
        val directory = directoryFor(spec)
        val marker = File(directory, MARKER_NAME)
        if (!marker.isFile) return null

        val properties = runCatching {
            Properties().apply {
                marker.inputStream().use { input -> load(input) }
            }
        }.getOrNull() ?: return null
        if (properties.getProperty("fingerprint") != spec.fingerprint) return null

        val relativeEntry = properties.getProperty("entry")?.takeIf(String::isNotBlank) ?: return null
        val expectedEntryDigest = properties.getProperty("entrySha256")?.takeIf(String::isNotBlank) ?: return null
        val root = directory.toPath().toAbsolutePath().normalize()
        val entry = root.resolve(relativeEntry).normalize()
        if (!entry.startsWith(root) || !entry.toFile().isFile) return null
        val realRoot = runCatching { root.toRealPath() }.getOrNull() ?: return null
        val realEntry = runCatching { entry.toRealPath() }.getOrNull() ?: return null
        if (!realEntry.startsWith(realRoot)) return null
        val entryFile = realEntry.toFile()
        if (!FileDigest.sha256Hex(entryFile).equals(expectedEntryDigest, ignoreCase = true)) return null
        if (spec.executable && !entryFile.canExecute()) {
            if (!entryFile.setExecutable(true, false) || !entryFile.canExecute()) return null
        }
        return ResolvedToolAsset(spec = spec, directory = directory, file = entryFile)
    }

    internal fun writeMarker(spec: ToolAssetSpec, relativeEntry: String) {
        val directory = directoryFor(spec)
        val marker = File(directory, MARKER_NAME)
        val root = directory.toPath().toAbsolutePath().normalize()
        val entryPath = root.resolve(relativeEntry.replace('\\', '/')).normalize()
        require(entryPath.startsWith(root) && entryPath != root) {
            "Tool cache entry escapes its directory: " + relativeEntry
        }
        val entryFile = entryPath.toFile()
        require(entryFile.isFile) { "Tool cache entry does not exist: " + entryFile.absolutePath }
        val properties = Properties().apply {
            setProperty("fingerprint", spec.fingerprint)
            setProperty("entry", relativeEntry.replace(File.separatorChar, '/'))
            setProperty("entrySha256", FileDigest.sha256Hex(entryFile))
            setProperty("url", spec.downloadUrl)
        }
        marker.outputStream().use { output ->
            properties.store(output, "Verified Wasmline tool asset")
        }
    }

    private companion object {
        const val MARKER_NAME = ".wasmline-tool"
    }
}
