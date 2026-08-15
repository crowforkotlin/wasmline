package crow.wasmline.plugin.core.toolchain

import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Result of one external tool invocation. */

@InternalWasmlineToolingApi
data class ToolExecutionResult(val command: List<String>, val exitCode: Int, val output: String)

/** Indicates that an external tool timed out or returned a non-zero status. */

internal class ToolExecutionException(message: String, val result: ToolExecutionResult? = null) : IllegalStateException(message)

/** Executes pinned tools without involving a shell. */

@InternalWasmlineToolingApi
class ExternalToolRunner(private val defaultTimeoutMillis: Long = 120_000, private val logger: (String) -> Unit = {}) {
    fun run(
        executable: File,
        arguments: List<String>,
        workingDirectory: File? = null,
        environment: Map<String, String> = emptyMap(),
        timeoutMillis: Long = defaultTimeoutMillis,
    ): ToolExecutionResult {
        require(executable.isFile) { "Tool executable does not exist: " + executable.absolutePath }
        require(executable.canExecute()) { "Tool executable is not executable: " + executable.absolutePath }
        require(timeoutMillis > 0) { "Tool timeout must be positive." }
        workingDirectory?.let { require(it.isDirectory) { "Tool working directory does not exist: " + it.absolutePath } }

        val command = listOf(executable.absolutePath) + arguments
        val processBuilder = ProcessBuilder(command)
            .redirectErrorStream(true)
        workingDirectory?.let(processBuilder::directory)
        processBuilder.environment().putAll(environment)

        val process = processBuilder.start()
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val reader = thread(name = "wasmline-tool-output", isDaemon = true) {
            process.inputStream.bufferedReader().useLines { outputLines ->
                outputLines.forEach { line ->
                    lines += line
                    logger(line)
                }
            }
        }

        val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor()
        }
        reader.join()
        val result = ToolExecutionResult(
            command = command,
            exitCode = process.exitValue(),
            output = lines.joinToString(System.lineSeparator()),
        )
        if (!finished) {
            throw ToolExecutionException(
                "Tool timed out after " + timeoutMillis + " ms: " + describe(command),
                result,
            )
        }
        if (result.exitCode != 0) {
            throw ToolExecutionException(
                "Tool exited with code " + result.exitCode + ": " + describe(command) +
                    if (result.output.isBlank()) "" else System.lineSeparator() + result.output,
                result,
            )
        }
        return result
    }

    private fun describe(command: List<String>): String =
        command.joinToString(" ") { argument -> if (argument.any(Char::isWhitespace)) "\"$argument\"" else argument }
}
