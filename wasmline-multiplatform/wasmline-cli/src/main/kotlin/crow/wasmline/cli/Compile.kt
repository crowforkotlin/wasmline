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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
        echo("Using Wasmtime min: ${wasmtimeExec.absolutePath}")

        val resolvedName = name ?: inputFile.nameWithoutExtension
        val outputDir = File(outputRoot, "$resolvedName-$version")
        if (!outputDir.exists()) outputDir.mkdirs()

        val finalTargets = if (targets.isEmpty()) DEFAULT_TARGETS else targets

        echo("Input File: ${inputFile.name}")
        echo("Product name: $resolvedName")
        echo("Output: ${outputDir.absolutePath}")

        val artifacts = compileAll(wasmtimeExec, inputFile, outputDir, resolvedName, finalTargets) { echo(it) }

        if (artifacts.any { it.type != WasmlineArtifactType.WASM }) {
            val debugDir = File(outputDir, "debug")
            if (!debugDir.exists()) debugDir.mkdirs()
            writeCompileResult(inputFile, debugDir, artifacts)
            echo("--------------------------------------------------")
            echo("Compile result written to: ${File(debugDir, COMPILE_RESULT_FILE).absolutePath}")
            echo("Total artifacts: ${artifacts.size}")
        } else {
            echo("Error: No .cwasm or .pwasm artifacts compiled successfully.", err = true)
            throw ProgramResult(1)
        }
    }

    companion object {
        const val COMPILE_RESULT_FILE = "compile-result.json"

        /**
         * Maps shorthand target names (used in shell scripts and CLI args) to
         * standard Rust/LLVM target triples required by `wasmtime compile --target`.
         *
         * Without this mapping, shorthand names like `aarch64-android` are parsed by
         * wasmtime as `{arch}-{vendor}` with no OS, producing cwasm artifacts whose
         * metadata says `os=unknown` — causing "Module was compiled for operating
         * system 'unknown'" errors on the target device.
         */
        private val TARGET_ALIASES = mapOf(
            "x86_64-linux" to "x86_64-unknown-linux-gnu",
            "aarch64-linux" to "aarch64-unknown-linux-gnu",
            "aarch64-android" to "aarch64-linux-android",
            "armv7-android" to "armv7-linux-androideabi",
            "x86-android" to "i686-linux-android",
            "x86_64-android" to "x86_64-linux-android",
            "aarch64-macos" to "aarch64-apple-darwin",
            "x86_64-macos" to "x86_64-apple-darwin",
            "aarch64-ios" to "aarch64-apple-ios",
            "aarch64-ios-sim" to "aarch64-apple-ios-sim",
            "x86_64-windows" to "x86_64-pc-windows-msvc",
        )

        val DEFAULT_TARGETS = listOf(
            "pulley64",
            "x86_64-linux",
            "aarch64-linux",
            "aarch64-android",
            "armv7-android",
            "x86-android",
            "aarch64-macos",
            "aarch64-ios",
            "x86_64-windows"
        )

        /**
         * Resolve a shorthand target name to a standard Rust/LLVM triple.
         * If the input is already a full triple (or `pulley64`), it is returned as-is.
         */
        fun normalizeTarget(target: String): String {
            return TARGET_ALIASES[target] ?: target
        }

        /**
         * Prepare the browser `.wasm` artifact and compile native target artifacts.
         *
         * 2026-02-12 02:46:56
         * @param productName artifact name prefix (e.g., "manga"), output file name is manga-target.cwasm
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
            copyBrowserArtifact(inputFile, outputDir, productName, echo)?.let(artifacts::add)
            targets.forEach { target ->
                val artifact = compileTarget(wasmtimeExec, inputFile, outputDir, productName, target, echo)
                if (artifact != null) {
                    artifacts.add(artifact)
                }
            }
            return artifacts
        }

        fun copyBrowserArtifact(
            inputFile: File,
            outputDir: File,
            productName: String,
            echo: (String) -> Unit
        ): WasmlineArtifact? {
            val outFile = File(outputDir, "$productName.wasm")
            return try {
                Files.copy(inputFile.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                echo("--------------------------------------------------")
                echo("Copying browser wasm artifact")
                echo("Output: ${outFile.absolutePath}")
                echo("Success: ${outFile.name}")
                WasmlineArtifact(
                    type = WasmlineArtifactType.WASM,
                    url = outFile.name,
                    sha256 = sha256Hex(outFile),
                    targetCpu = "wasmjs",
                    targetOs = "browser",
                    is64Bit = true
                )
            } catch (e: Exception) {
                echo("Failed to copy browser wasm artifact: ${e.message}")
                null
            }
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

            val wasmtimeTarget = normalizeTarget(target)

            val command = mutableListOf(
                executable.absolutePath,
                "compile",
                inputFile.absolutePath,
                "-o", outFile.absolutePath,
                "--target", wasmtimeTarget,
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
            val normalized = normalizeTarget(target)
            if (normalized == "pulley64") return "pulley64" to "pulley"
            val parts = normalized.split("-")
            val cpu = parts[0]
            // Standard triple: {arch}-{vendor}-{os}[-{env}], OS is the 3rd segment.
            // Fallback for 2-segment input: treat parts[1] as OS.
            val rawOs = when {
                parts.size >= 3 -> parts[2]
                parts.size == 2 -> parts[1]
                else -> return cpu to null
            }
            return cpu to normalizeOs(rawOs)
        }

        private fun normalizeOs(raw: String): String {
            return when {
                "android" in raw -> "android"
                "darwin" in raw -> "macos"
                "ios" in raw -> "ios"
                "linux" in raw -> "linux"
                "windows" in raw -> "windows"
                else -> raw
            }
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
                listOf("wasmtime-min.exe", "wasmtime.exe")
            } else {
                listOf("wasmtime-min", "wasmtime")
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
