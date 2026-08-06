package crow.wasmline.plugin.core.component

import crow.wasmline.plugin.core.toolchain.ExternalToolRunner
import crow.wasmline.plugin.core.toolchain.ToolExecutionResult
import java.io.File

/** Operations required by the shared Component build pipeline. */
interface WasmTools {
    fun version(): String

    /** Verifies the selected wasm-tools version before using the pipeline. */
    fun verify(expectedVersion: String): String = verifyToolVersion("wasm-tools", version(), expectedVersion)

    fun embedWit(witPath: File, inputWasm: File, outputWasm: File, world: String? = null): ToolExecutionResult

    fun createComponent(
        embeddedWasm: File,
        adapter: File,
        outputComponent: File,
        adapterModule: String = "wasi_snapshot_preview1",
    ): ToolExecutionResult

    fun validate(component: File): ToolExecutionResult

    fun inspectWit(component: File): String
}

/** Typed wrapper around the wasm-tools Component Model commands. */
class WasmToolsTool(private val executable: File, private val runner: ExternalToolRunner = ExternalToolRunner()) : WasmTools {
    private val silentRunner = ExternalToolRunner()

    init {
        require(executable.isFile) { "wasm-tools executable does not exist: " + executable.absolutePath }
        require(executable.canExecute()) { "wasm-tools executable is not executable: " + executable.absolutePath }
    }

    /** Returns the exact version output reported by wasm-tools. */
    override fun version(): String = silentRunner.run(executable, listOf("--version")).output.trim()

    /** Verifies the selected binary and every Component command used below. */
    override fun verify(expectedVersion: String): String {
        val output = super<WasmTools>.verify(expectedVersion)
        listOf(
            listOf("component", "embed", "--help"),
            listOf("component", "new", "--help"),
            listOf("validate", "--help"),
            listOf("component", "wit", "--help"),
        ).forEach { arguments -> silentRunner.run(executable, arguments) }
        return output
    }

    /** Embeds WIT metadata into a Core Wasm module. */
    override fun embedWit(witPath: File, inputWasm: File, outputWasm: File, world: String?): ToolExecutionResult {
        require(witPath.exists()) { "WIT path does not exist: " + witPath.absolutePath }
        require(inputWasm.isFile) { "Core Wasm input does not exist: " + inputWasm.absolutePath }
        outputWasm.parentFile?.mkdirs()

        val arguments = mutableListOf("component", "embed")
        world?.takeIf(String::isNotBlank)?.let {
            arguments += listOf("--world", it)
        }
        arguments += listOf(witPath.absolutePath, inputWasm.absolutePath, "-o", outputWasm.absolutePath)
        val result = runner.run(executable, arguments)
        requireOutput(outputWasm, "component embed")
        return result
    }

    /** Creates a Component Wasm using the WASI Preview 1 reactor adapter. */
    override fun createComponent(embeddedWasm: File, adapter: File, outputComponent: File, adapterModule: String): ToolExecutionResult {
        require(embeddedWasm.isFile) { "Embedded Core Wasm does not exist: " + embeddedWasm.absolutePath }
        require(adapter.isFile) { "WASI adapter does not exist: " + adapter.absolutePath }
        require(adapterModule.isNotBlank()) { "WASI adapter module name must not be blank." }
        outputComponent.parentFile?.mkdirs()

        val result = runner.run(
            executable,
            listOf(
                "component",
                "new",
                embeddedWasm.absolutePath,
                "--adapt",
                adapterModule + "=" + adapter.absolutePath,
                "-o",
                outputComponent.absolutePath,
            ),
        )
        requireOutput(outputComponent, "component new")
        return result
    }

    /** Validates a finished Component Wasm. */
    override fun validate(component: File): ToolExecutionResult {
        require(component.isFile) { "Component Wasm does not exist: " + component.absolutePath }
        return runner.run(executable, listOf("validate", component.absolutePath))
    }

    /** Extracts the WIT world represented by a Component Wasm. */
    override fun inspectWit(component: File): String {
        require(component.isFile) { "Component Wasm does not exist: " + component.absolutePath }
        return silentRunner.run(executable, listOf("component", "wit", component.absolutePath)).output
    }

    private fun requireOutput(output: File, operation: String) {
        check(output.isFile && output.length() > 0) {
            "wasm-tools " + operation + " did not produce " + output.absolutePath + "."
        }
    }
}
