package crow.wasmline.plugin.core.toolchain

import crow.wasmline.plugin.core.util.PlatformDetector

/** Resolves locked tools from a cache and optionally downloads missing assets. */
class ToolResolver(
    private val cache: ToolCache,
    private val downloader: ToolDownloader,
) {
    suspend fun resolve(
        tool: WasmlineTool,
        version: String = ToolchainCatalog.defaultVersion(tool),
        platform: String = if (tool == WasmlineTool.WASI_PREVIEW1_REACTOR_ADAPTER) {
            ToolchainCatalog.UNIVERSAL_PLATFORM
        } else {
            PlatformDetector.detectPlatform()
        },
        autoDownload: Boolean = true,
        force: Boolean = false,
        githubToken: String? = null,
    ): ResolvedToolAsset {
        val spec = ToolchainCatalog.requireAsset(tool, version, platform)
        if (!force) cache.resolve(spec)?.let { return it }
        check(autoDownload) {
            "Missing verified " + tool.name.lowercase() + " " + version + " for " + platform +
                " in " + cache.rootDirectory.absolutePath + "."
        }
        return downloader.resolveOrDownload(spec, cache, githubToken, force)
    }
}
