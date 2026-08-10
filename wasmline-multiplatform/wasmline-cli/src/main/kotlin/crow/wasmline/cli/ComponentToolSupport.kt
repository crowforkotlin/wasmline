package crow.wasmline.cli

import crow.wasmline.plugin.core.toolchain.ToolCache
import crow.wasmline.plugin.core.toolchain.ToolDownloader
import crow.wasmline.plugin.core.toolchain.ToolResolver
import crow.wasmline.plugin.core.toolchain.ToolchainCatalog
import crow.wasmline.plugin.core.toolchain.WasmlineTool
import java.io.File

internal data class ComponentToolFiles(val wasmTools: File, val adapter: File)

internal suspend fun resolveWasmToolsFile(
    cacheDirectory: File,
    downloader: ToolDownloader,
    platform: String,
    wasmToolsPath: File?,
    wasmToolsVersion: String,
    githubToken: String?,
): File = wasmToolsPath ?: ToolResolver(ToolCache(cacheDirectory), downloader).resolve(
    tool = WasmlineTool.WASM_TOOLS,
    version = wasmToolsVersion,
    platform = platform,
    githubToken = githubToken,
).file

internal suspend fun resolveComponentToolFiles(
    cacheDirectory: File,
    downloader: ToolDownloader,
    platform: String,
    wasmToolsPath: File?,
    wasmToolsVersion: String,
    adapterPath: File?,
    githubToken: String?,
): ComponentToolFiles {
    val resolver = ToolResolver(ToolCache(cacheDirectory), downloader)
    return ComponentToolFiles(
        wasmTools = resolveWasmToolsFile(
            cacheDirectory = cacheDirectory,
            downloader = downloader,
            platform = platform,
            wasmToolsPath = wasmToolsPath,
            wasmToolsVersion = wasmToolsVersion,
            githubToken = githubToken,
        ),
        adapter = adapterPath ?: resolver.resolve(
            tool = WasmlineTool.WASI_PREVIEW1_REACTOR_ADAPTER,
            version = ToolchainCatalog.WASI_PREVIEW1_ADAPTER_VERSION,
            platform = ToolchainCatalog.UNIVERSAL_PLATFORM,
            githubToken = githubToken,
        ).file,
    )
}
