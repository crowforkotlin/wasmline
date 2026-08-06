package crow.wasmline.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import crow.wasmline.plugin.core.component.KotlinBindingsRequest
import crow.wasmline.plugin.core.component.WitBindgenTool
import crow.wasmline.plugin.core.toolchain.ExternalToolRunner
import crow.wasmline.plugin.core.toolchain.ToolCache
import crow.wasmline.plugin.core.toolchain.ToolDownloader
import crow.wasmline.plugin.core.toolchain.ToolResolver
import crow.wasmline.plugin.core.toolchain.ToolchainCatalog
import crow.wasmline.plugin.core.toolchain.WasmlineTool
import crow.wasmline.plugin.core.util.PlatformDetector
import kotlinx.coroutines.runBlocking
import java.io.File

/** Generates Kotlin/Wasm Component bindings from a WIT directory. */
class WitGenerate : CliktCommand(name = "generate") {
    private val witDirectory by option("--wit")
        .file(mustExist = true, canBeFile = false, canBeDir = true)
        .required()
    private val outputDirectory by option("-o", "--output")
        .file(canBeFile = false, canBeDir = true)
        .default(File("build/generated/wasmline/wit"))
    private val world by option("--world")
    private val kotlinImports by option("--kotlin-imports").default("impl.*")
    private val witBindgenPath by option("--wit-bindgen")
        .file(mustExist = true, canBeFile = true, canBeDir = false)
    private val version by option("-v", "--version").default(ToolchainCatalog.WIT_BINDGEN_VERSION)
    private val platform by option("-a", "--platform").default(PlatformDetector.detectPlatform())
    private val cacheDirectory by option("--cache")
        .file(canBeFile = false, canBeDir = true)
        .default(defaultToolCacheDirectory())

    override fun run() = runBlocking {
        val downloader = ToolDownloader(logger = ::echo)
        try {
            val resolved = witBindgenPath ?: ToolResolver(ToolCache(cacheDirectory), downloader).resolve(
                tool = WasmlineTool.WIT_BINDGEN,
                version = version,
                platform = platform,
                githubToken = System.getenv("GITHUB_TOKEN"),
            ).file
            WitBindgenTool(
                executable = resolved,
                runner = ExternalToolRunner(logger = ::echo),
            ).generateKotlin(
                KotlinBindingsRequest(
                    witDirectory = witDirectory,
                    outputDirectory = outputDirectory,
                    world = world,
                    kotlinImports = kotlinImports,
                    witBindgenVersion = version,
                ),
            )
            echo("Kotlin bindings written to: " + outputDirectory.absolutePath)
        } catch (error: Exception) {
            echo("Error: " + error.message, err = true)
            throw ProgramResult(1)
        } finally {
            downloader.close()
        }
    }
}
