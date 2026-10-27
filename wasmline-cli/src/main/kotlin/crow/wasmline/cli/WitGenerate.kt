package crow.wasmline.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.file
import crow.wasmline.plugin.core.component.WitBindgenLanguage
import crow.wasmline.plugin.core.component.WitBindgenTool
import crow.wasmline.plugin.core.component.WitBindingsRequest
import crow.wasmline.plugin.core.toolchain.ExternalToolRunner
import crow.wasmline.plugin.core.toolchain.ToolCache
import crow.wasmline.plugin.core.toolchain.ToolDownloader
import crow.wasmline.plugin.core.toolchain.ToolResolver
import crow.wasmline.plugin.core.toolchain.ToolchainCatalog
import crow.wasmline.plugin.core.toolchain.WasmlineTool
import crow.wasmline.plugin.core.util.PlatformDetector
import kotlinx.coroutines.runBlocking
import java.io.File

/** Generates guest Component bindings with the pinned `wit-bindgen` release. */
internal class WitGenerate : CliktCommand(name = "generate") {
    private val witDirectory by option("--wit")
        .file(mustExist = true, canBeFile = false, canBeDir = true)
        .required()
    private val outputDirectory by option("-o", "--output")
        .file(canBeFile = false, canBeDir = true)
        .default(File("build/generated/wasmline/wit"))
    private val language by option("--lang")
        .enum<WitBindgenLanguage>(ignoreCase = false) { it.generator }
        .default(WitBindgenLanguage.KOTLIN)
        .help("Guest generator: " + WitBindgenLanguage.tokens.joinToString())
    private val world by option("--world")
    private val kotlinImports by option("--kotlin-imports")
        .help("Kotlin packages imported by generated code; applies to --lang kotlin.")
    private val witBindgenPath by option("--wit-bindgen")
        .file(mustExist = true, canBeFile = true, canBeDir = false)
    private val version by option("-v", "--version").default(ToolchainCatalog.WIT_BINDGEN_VERSION)
    private val platform by option("-a", "--platform").default(PlatformDetector.detectPlatform())
    private val cacheDirectory by option("--cache")
        .file(canBeFile = false, canBeDir = true)
        .default(defaultToolCacheDirectory())

    override fun run() = runBlocking {
        val downloader = ToolDownloader(logger = ::echo)
        val imports = kotlinImports ?: if (language == WitBindgenLanguage.KOTLIN) DEFAULT_KOTLIN_IMPORTS else null
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
            ).generate(
                WitBindingsRequest(
                    witDirectory = witDirectory,
                    outputDirectory = outputDirectory,
                    language = language,
                    world = world,
                    kotlinImports = imports,
                    witBindgenVersion = version,
                ),
            )
            echo(language.displayName + " bindings written to: " + outputDirectory.absolutePath)
        } catch (error: Exception) {
            echo("Error: " + error.message, err = true)
            throw ProgramResult(1)
        } finally {
            downloader.close()
        }
    }

    private companion object {
        const val DEFAULT_KOTLIN_IMPORTS = "impl.*"
    }
}
