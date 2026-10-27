package crow.wasmline.plugin.core.component

import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import crow.wasmline.plugin.core.toolchain.ExternalToolRunner
import crow.wasmline.plugin.core.toolchain.ToolExecutionResult
import java.io.File

/** Input for guest binding generation through `wit-bindgen`. */

@InternalWasmlineToolingApi
data class WitBindingsRequest(
    val witDirectory: File,
    val outputDirectory: File,
    val language: WitBindgenLanguage = WitBindgenLanguage.KOTLIN,
    val world: String? = null,
    val kotlinImports: String? = null,
    val additionalArguments: List<String> = emptyList(),
    val witBindgenVersion: String? = null,
)

/** Typed wrapper around a `wit-bindgen` guest generator. */

@InternalWasmlineToolingApi
class WitBindgenTool(private val executable: File, private val runner: ExternalToolRunner = ExternalToolRunner()) {
    private val verificationRunner = ExternalToolRunner()

    init {
        require(executable.isFile) { "wit-bindgen executable does not exist: " + executable.absolutePath }
        require(executable.canExecute()) { "wit-bindgen executable is not executable: " + executable.absolutePath }
    }

    /** Returns the exact version output reported by wit-bindgen. */
    fun version(): String = verificationRunner.run(executable, listOf("--version")).output.trim()

    /** Verifies the selected binary reports [expectedVersion]. */
    fun verify(expectedVersion: String): String = verifyToolVersion("wit-bindgen", version(), expectedVersion)

    /** Verifies the pinned binary exposes [language]'s generator. */
    fun verifyGenerator(language: WitBindgenLanguage) {
        verificationRunner.run(executable, listOf(language.generator, "--help"))
    }

    /** Generates guest bindings for one WIT directory and world. */
    fun generate(request: WitBindingsRequest): ToolExecutionResult {
        require(request.witDirectory.isDirectory) {
            "WIT directory does not exist: " + request.witDirectory.absolutePath
        }
        require(!request.outputDirectory.exists() || request.outputDirectory.isDirectory) {
            "${request.language.displayName} binding output is not a directory: " + request.outputDirectory.absolutePath
        }
        require(request.language == WitBindgenLanguage.KOTLIN || request.kotlinImports == null) {
            "--kotlin-imports applies only to ${WitBindgenLanguage.KOTLIN.generator}, not ${request.language.generator}."
        }
        request.witBindgenVersion?.let(::verify)
        verifyGenerator(request.language)

        if (!request.outputDirectory.exists()) {
            check(request.outputDirectory.mkdirs()) {
                "Unable to create ${request.language.displayName} binding output directory: " +
                    request.outputDirectory.absolutePath
            }
        }
        removeStaleOutput(request.outputDirectory, request.language)

        val arguments = mutableListOf(request.language.generator)
        request.kotlinImports?.takeIf(String::isNotBlank)?.let {
            arguments += listOf("--kotlin-imports", it)
        }
        request.world?.takeIf(String::isNotBlank)?.let {
            arguments += listOf("--world", it)
        }
        arguments += request.language.requiredArguments
        arguments += request.additionalArguments
        arguments += request.witDirectory.absolutePath
        arguments += "--out-dir=" + request.outputDirectory.absolutePath

        val result = runner.run(executable, arguments)
        val produced = request.outputDirectory.walkTopDown()
            .any { it.isFile && it.extension.lowercase() in request.language.extensions }
        check(produced) {
            "wit-bindgen ${request.language.generator} completed without producing " +
                request.language.displayName + " source in " + request.outputDirectory.absolutePath + "."
        }
        return result
    }

    private fun removeStaleOutput(directory: File, language: WitBindgenLanguage) {
        directory.walkTopDown()
            .filter { it.isFile && language.ownsFile(it.name) }
            .forEach { file ->
                check(file.delete()) {
                    "Unable to remove stale ${language.displayName} binding: " + file.absolutePath
                }
            }
    }
}
