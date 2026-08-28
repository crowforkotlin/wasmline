package crow.wasmline.plugin.core.compiler

import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import crow.wasmline.plugin.core.aot.AotCompatibilityProfileSpec
import crow.wasmline.plugin.core.aot.WasmlineAotCompileOptions
import java.io.File

/**
 * Executes a verified Wasmtime compiler with the frozen Wasmline AOT profile.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
class WasmtimeCompiler(
    private val runner: WasmtimeCompilerRunner = ExternalWasmtimeCompilerRunner,
    private val logger: (String) -> Unit = {},
) {
    /** Verifies the compiler version and compile capability before matrix execution. */
    fun verify(executable: File, profile: AotCompatibilityProfileSpec) {
        require(executable.isFile) { "Wasmtime compiler does not exist: ${executable.absolutePath}" }
        require(executable.canExecute()) { "Wasmtime compiler is not executable: ${executable.absolutePath}" }
        val version = runner.run(executable, listOf("--version"))
        check(version.exitCode == 0 && parseWasmtimeVersion(version.output) == profile.wasmtimeVersion) {
            "AOT compiler does not report Wasmtime ${profile.wasmtimeVersion}: ${executable.absolutePath}"
        }
        val capability = runner.run(executable, listOf("compile", "--help"))
        check(capability.exitCode == 0) {
            "AOT compiler does not provide the compile subcommand: ${executable.absolutePath}"
        }
    }

    /** Compiles one matrix unit and returns its non-empty output file. */
    fun compile(executable: File, inputFile: File, outputFile: File, normalizedTarget: String, options: WasmlineAotCompileOptions): File {
        require(inputFile.isFile && inputFile.length() > 0) { "AOT input does not exist or is empty: ${inputFile.absolutePath}" }
        outputFile.parentFile?.let { parent -> check(parent.isDirectory || parent.mkdirs()) }
        if (outputFile.exists()) check(outputFile.delete()) { "Unable to remove stale AOT output: ${outputFile.absolutePath}" }
        val arguments = options.compilerArguments(
            inputFile = inputFile.absolutePath,
            outputFile = outputFile.absolutePath,
            normalizedTarget = normalizedTarget,
        )
        logger("Compiling AOT target $normalizedTarget")
        val result = runner.run(executable, arguments)
        result.output.lineSequence().filter(String::isNotBlank).forEach { logger("  [wasmtime] $it") }
        check(result.exitCode == 0) {
            "Wasmtime compile failed for $normalizedTarget with exit code ${result.exitCode}."
        }
        check(outputFile.isFile && outputFile.length() > 0) {
            "Wasmtime completed without producing AOT output: ${outputFile.absolutePath}"
        }
        return outputFile
    }

    /**
     * Defines supported target aliases and compiler identity helpers.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    companion object {
        val defaultTargets: List<String> = listOf(
            "pulley32",
            "pulley64",
            "x86_64-linux",
            "aarch64-linux",
            "aarch64-android",
            "aarch64-macos",
            "x86_64-windows",
        )

        /** Returns the Wasmtime target triple for a configured target name. */
        fun normalizeTarget(target: String): String = TARGET_ALIASES[target] ?: target

        /** Parses a normalized target into architecture and canonical operating system. */
        fun parseTarget(target: String): Pair<String, String?> {
            val normalized = normalizeTarget(target)
            val architecture = normalized.substringBefore('-')
            if (architecture == "pulley32" || architecture == "pulley64") return architecture to null
            val parts = normalized.split('-')
            val rawOperatingSystem = when {
                parts.size >= 3 -> parts[2]
                parts.size == 2 -> parts[1]
                else -> return architecture to null
            }
            return architecture to normalizeOperatingSystem(rawOperatingSystem)
        }

        /** Reads the exact x.y.z version reported by a Wasmtime executable. */
        fun detectWasmtimeVersion(executable: File): String? = runCatching {
            val result = ExternalWasmtimeCompilerRunner.run(executable, listOf("--version"))
            if (result.exitCode == 0) parseWasmtimeVersion(result.output) else null
        }.getOrNull()

        /** Parses the stable x.y.z token from Wasmtime version output. */
        internal fun parseWasmtimeVersion(output: String): String? =
            Regex("(?:^|\\s)wasmtime\\s+(\\d+\\.\\d+\\.\\d+)(?:\\s|$)", RegexOption.IGNORE_CASE)
                .find(output)
                ?.groupValues
                ?.get(1)

        private fun normalizeOperatingSystem(value: String): String = when {
            "android" in value -> "android"
            "darwin" in value || "macos" in value -> "macos"
            "ios" in value -> "ios"
            "linux" in value -> "linux"
            "windows" in value -> "windows"
            else -> value
        }

        private val TARGET_ALIASES: Map<String, String> = mapOf(
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
    }
}

/**
 * Captures one local Wasmtime process result for deterministic tests and diagnostics.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
data class WasmtimeCompilerProcessResult(val exitCode: Int, val output: String)

/**
 * Executes Wasmtime compiler processes.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
fun interface WasmtimeCompilerRunner {
    /** Runs one executable invocation. */
    fun run(executable: File, arguments: List<String>): WasmtimeCompilerProcessResult
}

/**
 * Implements compiler process execution without a shell.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
private object ExternalWasmtimeCompilerRunner : WasmtimeCompilerRunner {
    override fun run(executable: File, arguments: List<String>): WasmtimeCompilerProcessResult {
        val process = ProcessBuilder(listOf(executable.absolutePath) + arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return WasmtimeCompilerProcessResult(process.waitFor(), output)
    }
}
