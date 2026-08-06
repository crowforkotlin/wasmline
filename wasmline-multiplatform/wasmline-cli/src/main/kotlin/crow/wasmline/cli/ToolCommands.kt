package crow.wasmline.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import crow.wasmline.plugin.core.toolchain.ToolCache
import crow.wasmline.plugin.core.toolchain.ToolDownloader
import crow.wasmline.plugin.core.toolchain.ToolResolver
import crow.wasmline.plugin.core.toolchain.ToolchainCatalog
import crow.wasmline.plugin.core.toolchain.WasmlineTool
import crow.wasmline.plugin.core.util.PlatformDetector
import kotlinx.coroutines.runBlocking
import java.io.File

/** Downloads one locked Component Model tool into the Wasmline cache. */
class ToolDownload : CliktCommand(name = "download") {
    private val toolName by option("--tool").required()
    private val version by option("-v", "--version")
    private val platform by option("-a", "--platform").default(PlatformDetector.detectPlatform())
    private val cacheDirectory by option("--cache")
        .file(canBeFile = false, canBeDir = true)
        .default(defaultToolCacheDirectory())
    private val force by option("-f", "--force").flag(default = false)

    override fun run() = runBlocking {
        val tool = parseTool(toolName)
        val selectedVersion = version ?: ToolchainCatalog.defaultVersion(tool)
        val downloader = ToolDownloader(logger = ::echo)
        try {
            val resolved = ToolResolver(ToolCache(cacheDirectory), downloader).resolve(
                tool = tool,
                version = selectedVersion,
                platform = platform,
                force = force,
                githubToken = System.getenv("GITHUB_TOKEN"),
            )
            echo("Tool: " + resolved.spec.tool.name.lowercase())
            echo("Version: " + resolved.spec.version)
            echo("Path: " + resolved.file.absolutePath)
        } catch (error: Exception) {
            echo("Error: " + error.message, err = true)
            throw ProgramResult(1)
        } finally {
            downloader.close()
        }
    }
}

internal fun parseTool(value: String): WasmlineTool = when (value.trim().lowercase().replace('_', '-')) {
    "wit-bindgen",
    "wit",
    -> WasmlineTool.WIT_BINDGEN

    "wasm-tools",
    "wasmtool",
    -> WasmlineTool.WASM_TOOLS

    "wasi-preview1-reactor-adapter",
    "wasi-adapter",
    "adapter",
    -> WasmlineTool.WASI_PREVIEW1_REACTOR_ADAPTER

    else -> error("Unknown Component tool '$value'. Expected wit-bindgen, wasm-tools or wasi-adapter.")
}

internal fun defaultToolCacheDirectory(): File = File(System.getProperty("user.home"), ".wasmline/tools")
