package crow.wasmline.plugin.core.component

import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import crow.wasmline.plugin.core.toolchain.ExternalToolRunner
import crow.wasmline.plugin.core.toolchain.ToolExecutionResult
import java.io.File

/** Input for Kotlin guest binding generation. */

@InternalWasmlineToolingApi
data class KotlinBindingsRequest(
    val witDirectory: File,
    val outputDirectory: File,
    val world: String? = null,
    val kotlinImports: String? = "impl.*",
    val additionalArguments: List<String> = emptyList(),
    val witBindgenVersion: String? = null,
)

/** Typed wrapper around the wit-bindgen Kotlin generator. */

@InternalWasmlineToolingApi
class WitBindgenTool(private val executable: File, private val runner: ExternalToolRunner = ExternalToolRunner()) {
    private val verificationRunner = ExternalToolRunner()

    init {
        require(executable.isFile) { "wit-bindgen executable does not exist: " + executable.absolutePath }
        require(executable.canExecute()) { "wit-bindgen executable is not executable: " + executable.absolutePath }
    }

    /** Returns the exact version output reported by wit-bindgen. */
    fun version(): String = verificationRunner.run(executable, listOf("--version")).output.trim()

    /** Verifies the selected binary and the Kotlin generator command. */
    fun verify(expectedVersion: String): String {
        val output = verifyToolVersion("wit-bindgen", version(), expectedVersion)
        verificationRunner.run(executable, listOf("kotlin", "--help"))
        return output
    }

    /** Generates Kotlin guest bindings for one WIT directory and world. */
    fun generateKotlin(request: KotlinBindingsRequest): ToolExecutionResult {
        require(request.witDirectory.isDirectory) {
            "WIT directory does not exist: " + request.witDirectory.absolutePath
        }
        require(!request.outputDirectory.exists() || request.outputDirectory.isDirectory) {
            "Kotlin binding output is not a directory: " + request.outputDirectory.absolutePath
        }
        if (!request.outputDirectory.exists()) {
            check(request.outputDirectory.mkdirs()) {
                "Unable to create Kotlin binding output directory: " + request.outputDirectory.absolutePath
            }
        }
        request.witBindgenVersion?.let(::verify)

        request.outputDirectory.walkTopDown()
            .filter { it.isFile && it.extension.equals("kt", ignoreCase = true) }
            .forEach { file ->
                check(file.delete()) { "Unable to remove stale Kotlin binding: " + file.absolutePath }
            }

        val arguments = mutableListOf("kotlin")
        request.kotlinImports?.takeIf(String::isNotBlank)?.let {
            arguments += listOf("--kotlin-imports", it)
        }
        request.world?.takeIf(String::isNotBlank)?.let {
            arguments += listOf("--world", it)
        }
        arguments += request.additionalArguments
        arguments += request.witDirectory.absolutePath
        arguments += "--out-dir=" + request.outputDirectory.absolutePath

        val result = runner.run(executable, arguments)
        check(request.outputDirectory.walkTopDown().any { it.isFile && it.extension == "kt" }) {
            "wit-bindgen completed without producing Kotlin source in " + request.outputDirectory.absolutePath + "."
        }
        return result
    }
}
