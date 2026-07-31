package crow.wasmline.plugin.core.compiler

import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineArtifactType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Compiles WebAssembly modules with Wasmtime.
 *
 * 2026/7/31
 * @author crowforkotlin
 */
class WasmtimeCompiler {

    companion object {
        const val COMPILE_RESULT_FILE = "compile-result.json"

        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
        }

        private val targetAliases = mapOf(
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

        val defaultTargets = listOf(
            "pulley64",
            "x86_64-linux",
            "aarch64-linux",
            "aarch64-android",
            "armv7-android",
            "x86-android",
            "aarch64-macos",
            "aarch64-ios",
            "x86_64-windows",
        )

        /** Returns the Wasmtime target triple for a configured target name. */
        fun normalizeTarget(target: String): String = targetAliases[target] ?: target

        /** Finds a Wasmtime executable in the base directory or its child directories. */
        fun findWasmtimeInDirectory(baseDir: File, platform: String? = null): File? {
            findWasmtimeExecutable(baseDir)?.let { return it }
            if (!baseDir.isDirectory) return null

            return baseDir.listFiles()
                ?.asSequence()
                ?.filter(File::isDirectory)
                ?.filter { platform == null || it.name.contains(platform) }
                ?.sortedByDescending(File::getName)
                ?.mapNotNull(::findWasmtimeExecutable)
                ?.firstOrNull()
        }

        /** Finds a Wasmtime executable below the given directory. */
        fun findWasmtimeExecutable(directory: File): File? {
            if (!directory.exists()) return null
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val candidateNames = if (isWindows) {
                listOf("wasmtime-min.exe", "wasmtime.exe")
            } else {
                listOf("wasmtime-min", "wasmtime")
            }
            return candidateNames.firstNotNullOfOrNull { targetName ->
                directory.walk().firstOrNull { it.isFile && it.name.equals(targetName, ignoreCase = true) }
            }?.also { executable ->
                if (!isWindows) executable.setExecutable(true)
            }
        }
    }

    /** Compiles a module for each target and copies the browser artifact. */
    fun compileAll(
        wasmtimeExec: File,
        inputWasm: File,
        outputDir: File,
        productName: String,
        targets: Collection<String> = defaultTargets,
        wasmtimeVersion: String? = null,
        logger: (String) -> Unit = {},
    ): List<WasmlineArtifact> {
        val artifacts = mutableListOf<WasmlineArtifact>()
        copyBrowserArtifact(inputWasm, outputDir, productName, logger)?.let(artifacts::add)

        val effectiveTargets = if (targets.isEmpty()) defaultTargets else targets
        effectiveTargets.forEach { target ->
            compileTarget(wasmtimeExec, inputWasm, outputDir, productName, target, wasmtimeVersion, logger)
                ?.let(artifacts::add)
        }
        return artifacts
    }

    /** Writes the compilation result used by manifest creation. */
    fun writeCompileResult(
        inputFile: File,
        debugDir: File,
        artifacts: List<WasmlineArtifact>,
        wasmtimeVersion: String,
    ) {
        debugDir.mkdirs()
        val result = CompileResult(wasmtimeVersion, inputFile.name, artifacts)
        File(debugDir, COMPILE_RESULT_FILE).writeText(json.encodeToString(result))
    }

    /** Reads a compilation result written by [writeCompileResult]. */
    fun readCompileResult(file: File): CompileResult = json.decodeFromString(file.readText())

    private fun copyBrowserArtifact(
        inputFile: File,
        outputDir: File,
        productName: String,
        logger: (String) -> Unit,
    ): WasmlineArtifact? {
        val outFile = File(outputDir, "$productName.wasm")
        return runCatching {
            Files.copy(inputFile.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            logger("Copying browser wasm artifact: ${outFile.name}")
            WasmlineArtifact(
                type = WasmlineArtifactType.WASM,
                url = outFile.name,
                sha256 = sha256Hex(outFile),
                targetCpu = "wasmjs",
                targetOs = "browser",
            )
        }.onFailure { logger("Failed to copy browser wasm artifact: ${it.message}") }.getOrNull()
    }

    private fun compileTarget(
        executable: File,
        inputFile: File,
        outputDir: File,
        productName: String,
        target: String,
        wasmtimeVersion: String?,
        logger: (String) -> Unit,
    ): WasmlineArtifact? {
        val isPulley = target.contains("pulley")
        val extension = if (isPulley) "pwasm" else "cwasm"
        val outFileName = "$productName-$target.$extension"
        val outFile = File(outputDir, outFileName)
        logger("Compiling for target: $target")

        val command = listOf(
            executable.absolutePath,
            "compile",
            inputFile.absolutePath,
            "-o", outFile.absolutePath,
            "--target", normalizeTarget(target),
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
        ) + if (isPulley) listOf("-O", "pipeline=pulley64") else emptyList()

        return runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { logger("  [wasmtime] $it") }
            }
            check(process.waitFor() == 0 && outFile.exists()) {
                "wasmtime did not produce $outFileName"
            }
            val (cpu, os) = parseTarget(target)
            logger("Success: $outFileName")
            WasmlineArtifact(
                type = if (isPulley) WasmlineArtifactType.PWASM else WasmlineArtifactType.CWASM,
                url = outFileName,
                sha256 = sha256Hex(outFile),
                targetCpu = cpu,
                targetOs = os,
                targetCompilerVersion = wasmtimeVersion?.let { "wasmtime-$it" },
                is64Bit = target.contains("64") || target.contains("aarch64"),
            )
        }.onFailure { logger("Failed to compile for $target: ${it.message}") }.getOrNull()
    }

    private fun parseTarget(target: String): Pair<String, String?> {
        val normalized = normalizeTarget(target)
        if (normalized == "pulley64") return "pulley64" to "pulley"
        val parts = normalized.split("-")
        val cpu = parts.first()
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
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
