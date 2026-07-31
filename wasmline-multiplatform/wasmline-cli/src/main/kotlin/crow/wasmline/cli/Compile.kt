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
import crow.wasmline.loader.model.WasmlineArtifactType
import crow.wasmline.plugin.core.compiler.WasmtimeCompiler
import java.io.File

/**
 * Compile task — compiles .wasm to platform-specific AOT/Pulley artifacts
 * and writes compile-result.json for the [Manifest] command to consume.
 *
 * Output layout:
 * ```
 * build/wasmline/output/{name}-{version}/
 *   ├── {name}-pulley64.pwasm
 *   ├── {name}-aarch64-android.cwasm
 *   ├── {name}.wasm
 *   └── debug/
 *       └── compile-result.json
 * ```
 *
 * 2026/1/20 00:16
 * @author crowforkotlin
 * @formatter:on
 */
class Compile : CliktCommand(name = "compile") {

    private val inputFile by option("-i", "--input").file(mustExist = true, canBeFile = true, canBeDir = false).required()
    private val name by option("-n", "--name")
    private val version by option("-v", "--version").default("1.0.0")
    private val outputRoot by option("-o", "--output")
        .file(canBeFile = false, canBeDir = true)
        .default(File("build/wasmline/output"))
    private val wasmtimeDir by option("-wt", "--wasmtime")
        .file(mustExist = true, canBeDir = true, canBeFile = false)
        .required()
    private val targets by option("-a", "--arch").multiple().unique()

    override fun run() {
        val executable = WasmtimeCompiler.findWasmtimeInDirectory(wasmtimeDir)
        if (executable == null) {
            echo("Error: Could not find wasmtime in ${wasmtimeDir.absolutePath}", err = true)
            throw ProgramResult(1)
        }
        val productName = name ?: inputFile.nameWithoutExtension
        val outputDir = File(outputRoot, "$productName-$version").apply { mkdirs() }
        val artifacts = WasmtimeCompiler().compileAll(
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
        val debugDir = File(outputDir, "debug")
        WasmtimeCompiler().writeCompileResult(inputFile, debugDir, artifacts, BuildConfig.WASMTIME_VERSION)
        echo("Compile result written to: ${File(debugDir, WasmtimeCompiler.COMPILE_RESULT_FILE).absolutePath}")
    }
}
