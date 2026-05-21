@file:Suppress("SpellCheckingInspection")

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
import crow.wasmline.cli.extensions.baseJson
import crow.wasmline.cli.models.CompileResult
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineArtifactType
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Compile task — compiles .wasm to platform-specific AOT/Pulley artifacts
 * and writes compile-result.json for the [Manifest] command to consume.
 *
 * Output layout:
 * ```
 * build/wasmline/output/{name}-{version}/
 *   ├── {name}-pulley64.pwasm
 *   ├── {name}-aarch64-android.cwasm
 *   └── debug/
 *       └── compile-result.json
 * ```
 *
 * 2026/1/20 00:16
 * @author crowforkotlin
 * @formatter:on
 */
class Compile : CliktCommand(name = "compile") {

    // -i --input: input wasm file
    val inputFile by option("-i", "--input")
        .file(mustExist = true, canBeFile = true, canBeDir = false)
        .required()
        .help("Input .wasm file path")

    // -n --name: product name, the default is the input file name (excluding extension)
    val name by option("-n", "--name")
        .help("Product name for output artifacts (e.g., manga). Default: input file name without extension")

    // --version: version, used to form the output directory name {name}-{version}
    private val version by option("-v", "--version")
        .default("1.0.0")
        .help("Version string for output directory. Default: 1.0.0")

    // -o --output: Output root directory
    val outputRoot by option("-o", "--output")
        .file(canBeFile = false, canBeDir = true)
        .default(File("build/wasmline/output"))
        .help("Output root directory. Default: ./build/wasmline/output")

    // -wt --wasmtime: The root directory where the wasmtime tool is located
    val wasmtimeDir by option("-wt", "--wasmtime")
        .file(mustExist = true, canBeDir = true, canBeFile = false)
        .required()
        .help("Directory containing the wasmtime executable (downloaded via download command)")

    // -a --arch: target architecture, multiple selections possible
    val targets by option("-a", "--arch")
        .multiple()
        .unique()
        .help("Target architectures (e.g., pulley64, aarch64-linux-android). Default: all common targets")

    override fun run() {
        val wasmtimeExec = findWasmtimeExecutable(wasmtimeDir)
        if (wasmtimeExec == null) {
            echo("Error: Could not find 'wasmtime' executable in ${wasmtimeDir.absolutePath}", err = true)
            throw ProgramResult(1)
        }
        echo("Using Wasmtime: ${wasmtimeExec.absolutePath}")

        val resolvedName = name ?: inputFile.nameWithoutExtension
        val outputDir = File(outputRoot, "$resolvedName-$version")
        if (!outputDir.exists()) outputDir.mkdirs()

        val finalTargets = if (targets.isEmpty()) DEFAULT_TARGETS else targets

        echo("Input File: ${inputFile.name}")
        echo("Product name: $resolvedName")
        echo("Output: ${outputDir.absolutePath}")

        val artifacts = compileAll(wasmtimeExec, inputFile, outputDir, resolvedName, finalTargets) { echo(it) }

        if (artifacts.isNotEmpty()) {
            val debugDir = File(outputDir, "debug")
            if (!debugDir.exists()) debugDir.mkdirs()
            writeCompileResult(inputFile, debugDir, artifacts)
            echo("--------------------------------------------------")
            echo("Compile result written to: ${File(debugDir, COMPILE_RESULT_FILE).absolutePath}")
            echo("Total artifacts: ${artifacts.size}")
        } else {
            echo("Error: No artifacts compiled successfully.", err = true)
            throw ProgramResult(1)
        }
    }

    companion object {
        const val COMPILE_RESULT_FILE = "compile-result.json"

        val DEFAULT_TARGETS = listOf(
            "pulley64",
            "x86_64-linux",
            "aarch64-linux",
            "aarch64-android",
            "aarch64-macos",
            "aarch64-ios",
            "x86_64-windows"
        )

        /**
         * Compile all target architectures and return a list of successful artifacts
         *
         * 2026-02-12 02:46:56
         * @param productName 产物名称前缀（如 "manga"），生成文件名为 manga-target.cwasm
         * @author crowforkotlin
         */
        fun compileAll(
            wasmtimeExec: File,
            inputFile: File,
            outputDir: File,
            productName: String,
            targets: Collection<String>,
            echo: (String) -> Unit
        ): List<WasmlineArtifact> {
            val artifacts = mutableListOf<WasmlineArtifact>()
            targets.forEach { target ->
                val artifact = compileTarget(wasmtimeExec, inputFile, outputDir, productName, target, echo)
                if (artifact != null) {
                    artifacts.add(artifact)
                }
            }
            return artifacts
        }

        /**
         * Execute the compilation task of a single architecture, and return the corresponding [WasmlineArtifact] when successful
         *
         * 2026-02-12 02:46:35
         * @author crowforkotlin
         */
        fun compileTarget(
            executable: File,
            inputFile: File,
            outputDir: File,
            productName: String,
            target: String,
            echo: (String) -> Unit
        ): WasmlineArtifact? {
            val isPulley = target.contains("pulley")
            val extension = if (isPulley) "pwasm" else "cwasm"
            val outFileName = "$productName-$target.$extension"
            val outFile = File(outputDir, outFileName)

            echo("--------------------------------------------------")
            echo("Compiling for target: $target")
            echo("Output: ${outFile.absolutePath}")

            val command = mutableListOf(
                executable.absolutePath,
                "compile",
                inputFile.absolutePath,
                "-o", outFile.absolutePath,
                "--target", target,
                "-W", "gc=y",
                "-W", "function-references=y",
                "-W", "exceptions=y",
                "-W", "threads=n",
                "-W", "simd=n",
                "-W", "relaxed-simd=n",
                "-O", "static-memory-guard-size=0",
                "-O", "dynamic-memory-guard-size=0",
                "-O", "signals-based-traps=n",
                "-O", "opt-level=2",
                "-C", "cranelift-debug-verifier=no"
            )

            return try {
                val process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start()

                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { println("  [wasmtime] $it") }
                }

                val exitCode = process.waitFor()
                if (exitCode == 0 && outFile.exists()) {
                    echo("Success: $outFileName")
                    val (cpu, os) = parseTarget(target)
                    WasmlineArtifact(
                        type = if (isPulley) WasmlineArtifactType.PWASM else WasmlineArtifactType.CWASM,
                        url = outFileName,
                        sha256 = sha256Hex(outFile),
                        targetCpu = cpu,
                        targetOs = os,
                        targetCompilerVersion = "wasmtime-${BuildConfig.WASMTIME_VERSION}",
                        is64Bit = target.contains("64") || target.contains("aarch64")
                    )
                } else {
                    echo("Failed to compile for $target (Exit Code: $exitCode)")
                    null
                }
            } catch (e: Exception) {
                echo("Error executing wasmtime: ${e.message}")
                null
            }
        }

        /**
         * Write the compilation results to compile-result.json
         *
         * 2026-02-12 02:45:22
         * @author crowforkotlin
         */
        fun writeCompileResult(inputFile: File, debugDir: File, artifacts: List<WasmlineArtifact>) {
            val result = CompileResult(
                wasmtimeVersion = BuildConfig.WASMTIME_VERSION,
                inputFile = inputFile.name,
                artifacts = artifacts
            )
            File(debugDir, COMPILE_RESULT_FILE).writeText(baseJson.encodeToString(result))
        }

        fun parseTarget(target: String): Pair<String, String?> {
            val parts = target.split("-", limit = 2)
            return if (parts.size == 2) parts[0] to parts[1] else target to null
        }

        fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun findWasmtimeExecutable(directory: File): File? {
            val isWindows = System.getProperty("os.name").lowercase(Locale.getDefault()).contains("win")
            val candidateNames = if (isWindows) {
                listOf("wasmtime.exe", "wasmtime-min.exe")
            } else {
                listOf("wasmtime", "wasmtime-min")
            }
            return candidateNames.firstNotNullOfOrNull { targetName ->
                directory.walk()
                    .filter { it.isFile && it.name.equals(targetName, ignoreCase = true) }
                    .firstOrNull()
            }?.also {
                if (!isWindows) it.setExecutable(true)
            }
        }
    }
}
