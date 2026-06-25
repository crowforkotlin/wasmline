@file:Suppress("SpellCheckingInspection")

package crow.wasmline.gradle.internal

import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineArtifactType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

/**
 * Wasmtime AOT compiler wrapper used by [crow.wasmline.gradle.tasks.WasmlineAssembleTask].
 *
 * This class encapsulates the logic for invoking `wasmtime compile` to produce
 * platform-specific AOT (.cwasm) and Pulley (.pwasm) artifacts from a raw `.wasm` file.
 *
 * 2026/6/5
 * @author crowforkotlin
 */
internal object WasmtimeCompiler {

    private val baseJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * Maps shorthand target names to standard Rust/LLVM target triples
     * required by `wasmtime compile --target`.
     */
    private val TARGET_ALIASES = mapOf(
        "x86_64-linux" to "x86_64-unknown-linux-gnu",
        "aarch64-linux" to "aarch64-unknown-linux-gnu",
        "aarch64-android" to "aarch64-linux-android",
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
        "aarch64-macos",
        "aarch64-ios",
        "x86_64-windows",
    )

    /**
     * Locate the `wasmtime` executable inside [directory].
     *
     * Prefers `wasmtime-min` (v45.0.0 and earlier) and falls back to `wasmtime`
     * (v45.0.3+ where the min artifact only ships a single binary).
     *
     * @throws GradleException if the executable cannot be found.
     */
    fun resolveExecutable(directory: File): File {
        val isWindows = System.getProperty("os.name").lowercase(Locale.getDefault()).contains("win")
        val candidateNames = if (isWindows) listOf("wasmtime-min.exe", "wasmtime.exe") else listOf("wasmtime-min", "wasmtime")

        val executable = candidateNames.firstNotNullOfOrNull { name ->
            directory.walk()
                .filter { it.isFile && it.name.equals(name, ignoreCase = true) }
                .firstOrNull()
        }?.also {
            if (!isWindows) it.setExecutable(true)
        }

        return executable
            ?: throw GradleException(
                "wasmtime executable not found in '${directory.absolutePath}'. " +
                    "Run the 'wasmline download' CLI command first or configure the wasmtime directory " +
                    "via the wasmline { wasmtime { directory = file(\"...\") } } DSL block."
            )
    }

    /**
     * Compile the given [inputFile] (.wasm) into AOT / Pulley artifacts for
     * every target in [targets].
     *
     * When [targets] is empty, [DEFAULT_TARGETS] is used.
     *
     * @return the list of successfully produced [WasmlineArtifact]s.
     */
    fun compileAll(
        wasmtimeExec: File,
        inputFile: File,
        outputDir: File,
        productName: String,
        targets: Collection<String>,
        logger: Logger,
    ): List<WasmlineArtifact> {
        val artifacts = mutableListOf<WasmlineArtifact>()

        // Copy browser .wasm artifact
        copyBrowserArtifact(inputFile, outputDir, productName, logger)?.let(artifacts::add)

        val effectiveTargets = if (targets.isEmpty()) DEFAULT_TARGETS else targets
        for (target in effectiveTargets) {
            val artifact = compileTarget(wasmtimeExec, inputFile, outputDir, productName, target, logger)
            if (artifact != null) {
                artifacts.add(artifact)
            }
        }

        return artifacts
    }

    /**
     * Write `compile-result.json` into [debugDir].
     */
    fun writeCompileResult(inputFile: File, debugDir: File, artifacts: List<WasmlineArtifact>) {
        val result = CompileResultData(
            inputFile = inputFile.name,
            artifacts = artifacts,
        )
        File(debugDir, "compile-result.json").writeText(baseJson.encodeToString(CompileResultData.serializer(), result))
    }

    // ==================== Internal helpers ====================

    private fun copyBrowserArtifact(
        inputFile: File,
        outputDir: File,
        productName: String,
        logger: Logger,
    ): WasmlineArtifact? {
        val outFile = File(outputDir, "$productName.wasm")
        return try {
            Files.copy(inputFile.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            logger.lifecycle("Copying browser wasm artifact: ${outFile.name}")
            WasmlineArtifact(
                type = WasmlineArtifactType.WASM,
                url = outFile.name,
                sha256 = sha256Hex(outFile),
                targetCpu = "wasmjs",
                targetOs = "browser",
                is64Bit = true,
            )
        } catch (e: Exception) {
            logger.warn("Failed to copy browser wasm artifact: ${e.message}")
            null
        }
    }

    private fun compileTarget(
        executable: File,
        inputFile: File,
        outputDir: File,
        productName: String,
        target: String,
        logger: Logger,
    ): WasmlineArtifact? {
        val isPulley = target.contains("pulley")
        val extension = if (isPulley) "pwasm" else "cwasm"
        val outFileName = "$productName-$target.$extension"
        val outFile = File(outputDir, outFileName)

        logger.lifecycle("Compiling for target: $target")

        val wasmtimeTarget = TARGET_ALIASES[target] ?: target

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
            "-C", "cranelift-debug-verifier=no",
        )

        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { logger.lifecycle("  [wasmtime] $it") }
            }

            val exitCode = process.waitFor()
            if (exitCode == 0 && outFile.exists()) {
                logger.lifecycle("Success: $outFileName")
                val (cpu, os) = parseTarget(target)
                WasmlineArtifact(
                    type = if (isPulley) WasmlineArtifactType.PWASM else WasmlineArtifactType.CWASM,
                    url = outFileName,
                    sha256 = sha256Hex(outFile),
                    targetCpu = cpu,
                    targetOs = os,
                    is64Bit = target.contains("64") || target.contains("aarch64"),
                )
            } else {
                logger.warn("Failed to compile for $target (Exit Code: $exitCode)")
                null
            }
        } catch (e: Exception) {
            logger.warn("Error executing wasmtime: ${e.message}")
            null
        }
    }

    private fun parseTarget(target: String): Pair<String, String?> {
        val normalized = TARGET_ALIASES[target] ?: target
        if (normalized == "pulley64") return "pulley64" to "pulley"
        val parts = normalized.split("-")
        val cpu = parts[0]
        val rawOs = when {
            parts.size >= 3 -> parts[2]
            parts.size == 2 -> parts[1]
            else -> return cpu to null
        }
        return cpu to normalizeOs(rawOs)
    }

    private fun normalizeOs(raw: String): String = when {
        "android" in raw -> "android"
        "darwin" in raw -> "macos"
        "ios" in raw -> "ios"
        "linux" in raw -> "linux"
        "windows" in raw -> "windows"
        else -> raw
    }

    private fun sha256Hex(file: File): String {
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

    @Serializable
    private data class CompileResultData(
        val inputFile: String,
        val artifacts: List<WasmlineArtifact>,
    )
}
