package crow.wasmline.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.unique
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.long
import crow.wasmline.loader.model.WasmlineArtifactType
import crow.wasmline.plugin.core.compiler.WasmtimeCompiler
import crow.wasmline.plugin.core.manifest.ManifestSigner
import crow.wasmline.plugin.core.packaging.PluginPackager
import java.io.File

/**
 * Build command — orchestrates the full pipeline: compile → manifest → package.
 *
 * Output layout:
 * ```
 * build/wasmline/
 * ├── output/
 * │   └── {name}-{version}/
 * │       ├── manifest.wlm
 * │       ├── {name}.wasm
 * │       ├── {name}-pulley64.pwasm
 * │       ├── {name}-aarch64-android.cwasm
 * │       └── debug/
 * │           ├── compile-result.json
 * │           └── manifest.json
 * └── dist/
 *     └── {name}-{version}.zip
 * ```
 *
 * 2026/2/12
 * @author crowforkotlin
 * @formatter:on
 */
class Build : CliktCommand(name = "build") {

    private val inputFile by option("-i", "--input").file(mustExist = true, canBeFile = true, canBeDir = false).required()
    private val name by option("-n", "--name")
    private val wasmtimeDir by option("-wt", "--wasmtime").file(mustExist = true, canBeDir = true, canBeFile = false).required()
    private val targets by option("-a", "--arch").multiple().unique()
    private val pluginId by option("--plugin-id")
    private val version by option("-v", "--version").default("1.0.0")
    private val versionCode by option("--version-code").long().default(1L)
    private val minSdkVersion by option("--min-sdk").default(BuildConfig.VERSION)
    private val displayName by option("--display-name")
    private val author by option("--author")
    private val description by option("--description")
    private val iconUrl by option("--icon-url")
    private val homeUrl by option("--home-url")
    private val key by option("-k", "--key").required().help("Ed25519 private key: file path or hex string")

    override fun run() {
        val executable = WasmtimeCompiler.findWasmtimeInDirectory(wasmtimeDir)
        if (executable == null) {
            echo("Error: Could not find wasmtime in ${wasmtimeDir.absolutePath}", err = true)
            throw ProgramResult(1)
        }
        val productName = name ?: inputFile.nameWithoutExtension
        val outputDir = File("build/wasmline/output", "$productName-$version").apply { mkdirs() }
        val compiler = WasmtimeCompiler()
        val artifacts = compiler.compileAll(
            wasmtimeExec = executable,
            inputWasm = inputFile,
            outputDir = outputDir,
            productName = productName,
            targets = targets,
            wasmtimeVersion = BuildConfig.WASMTIME_VERSION,
            logger = ::echo,
        )
        if (artifacts.none { it.type != WasmlineArtifactType.WASM }) {
            echo("Error: No .cwasm or .pwasm artifacts compiled successfully.", err = true)
            throw ProgramResult(1)
        }
        compiler.writeCompileResult(inputFile, File(outputDir, "debug"), artifacts, BuildConfig.WASMTIME_VERSION)
        val manifestFile = ManifestSigner().createSignedManifest(
            artifacts = artifacts,
            pluginId = pluginId ?: productName,
            version = version,
            versionCode = versionCode,
            minSdkVersion = minSdkVersion,
            signingKey = key,
            outputDir = outputDir,
            displayName = displayName,
            author = author,
            description = description,
            iconUrl = iconUrl,
            homePageUrl = homeUrl,
            logger = ::echo,
        )
        val zipFile = PluginPackager.createZip(
            manifestFile = manifestFile,
            artifacts = artifacts,
            artifactDirectory = outputDir,
            destination = File("build/wasmline/dist", "$productName-$version.zip"),
            folderPrefix = "$productName-$version",
        )
        echo("Package written to: ${zipFile.absolutePath} (${zipFile.length()} bytes)")
    }
}
