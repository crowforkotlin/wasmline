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
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.plugin.core.compiler.WasmtimeCompiler
import crow.wasmline.plugin.core.manifest.ManifestSigner
import java.io.File

/**
 * Manifest command — reads compile-result.json and metadata options,
 * produces a signed manifest.wlm (Protobuf) and optionally manifest.json (debug).
 *
 * Expects the directory layout produced by [Compile]:
 * ```
 * {dir}/
 *   ├── *.wasm / *.cwasm / *.pwasm
 *   └── debug/
 *       └── compile-result.json
 * ```
 *
 * 2026/2/12
 * @author crowforkotlin
 * @formatter:on
 */
class Manifest : CliktCommand(name = "manifest") {

    private val dir by option("-d", "--dir").file(mustExist = true, canBeFile = false, canBeDir = true).required()
    private val pluginId by option("--plugin-id")
    private val version by option("--version").default("1.0.0")
    private val versionCode by option("--version-code").long().default(1L)
    private val minSdkVersion by option("--min-sdk").default(BuildConfig.VERSION)
    private val displayName by option("--display-name")
    private val author by option("--author")
    private val description by option("--description")
    private val iconUrl by option("--icon-url")
    private val homeUrl by option("--home-url")
    private val executionModel by option("--execution-model")
    private val invocationProtocol by option("--invocation-protocol")
    private val exportName by option("--export-name")
    private val contractMetadata by option("--contract-metadata").multiple().unique()
    private val key by option("-k", "--key").required().help("Ed25519 private key: file path or hex string")

    override fun run() {
        val resultFile = File(dir, "debug/${WasmtimeCompiler.COMPILE_RESULT_FILE}")
        if (!resultFile.isFile) {
            echo("Error: ${WasmtimeCompiler.COMPILE_RESULT_FILE} not found in ${resultFile.parent}", err = true)
            throw ProgramResult(1)
        }
        val result = WasmtimeCompiler().readCompileResult(resultFile)
        val resolvedPluginId = pluginId ?: File(result.inputFile).nameWithoutExtension
        val inferredArtifact = result.artifacts.firstOrNull {
            it.executionModel != WasmlineExecutionModel.CORE_WASM ||
                it.invocationProtocol != WasmlineInvocationProtocol.WASMLINE_CORE
        }
        val invocation = parseInvocationOptions(
            executionModelName = executionModel ?: inferredArtifact?.executionModel?.name ?: WasmlineExecutionModel.CORE_WASM.name,
            invocationProtocolName = invocationProtocol ?: inferredArtifact?.invocationProtocol?.name
                ?: WasmlineInvocationProtocol.WASMLINE_CORE.name,
            exportName = exportName ?: inferredArtifact?.exportName,
            contractMetadataEntries = contractMetadata,
        )
        ManifestSigner().createSignedManifest(
            artifacts = result.artifacts,
            pluginId = resolvedPluginId,
            version = version,
            versionCode = versionCode,
            minSdkVersion = minSdkVersion,
            signingKey = key,
            outputDir = dir,
            displayName = displayName,
            author = author,
            description = description,
            iconUrl = iconUrl,
            homePageUrl = homeUrl,
            executionModel = invocation.executionModel,
            invocationProtocol = invocation.invocationProtocol,
            exportName = invocation.exportName,
            contractMetadata = invocation.contractMetadata,
            logger = ::echo,
        )
    }
}
