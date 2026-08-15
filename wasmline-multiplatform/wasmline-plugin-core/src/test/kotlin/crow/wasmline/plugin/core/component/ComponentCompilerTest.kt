package crow.wasmline.plugin.core.component

import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.WasmlineArtifactType
import crow.wasmline.plugin.core.toolchain.FileDigest
import crow.wasmline.plugin.core.toolchain.ToolExecutionResult
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val INCOMPATIBLE_WASMTIME_VERSION = "0.0.0"

class ComponentCompilerTest {
    @Test
    fun compilesOneHostComponentToCwasmWithTheVerifiedEngineProfile() = withCompilerDirectory { root ->
        val compilerFile = executable(File(root, "wasmtime"))
        val input = File(root, "plugin-component.wasm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val output = File(root, "aot/plugin-x86_64-linux.cwasm")
        val runner = RecordingComponentCompilerRunner { arguments ->
            if (arguments.firstOrNull() == "compile" && arguments.getOrNull(1) != "--help") {
                output.parentFile.mkdirs()
                output.writeBytes(byteArrayOf(4, 5, 6, 7))
            }
        }
        val metadata = ComponentAotArtifactMetadata(
            contractMetadata = mapOf("wasmline.service.codec" to "protobuf"),
        )

        val result = ComponentCompiler(runner).compile(
            ComponentAotCompileRequest(
                wasmtimeCompiler = compilerFile,
                inputComponent = input,
                targets = listOf(
                    ComponentAotTarget(
                        target = "x86_64-linux",
                        backend = ComponentAotBackend.CRANELIFT,
                        outputFile = output,
                    ),
                ),
                wasmtimeVersion = "47.0.2",
                artifactMetadata = metadata,
            ),
        )

        assertEquals(listOf("--version"), runner.arguments[0])
        assertEquals(listOf("compile", "--help"), runner.arguments[1])
        assertEquals(expectedCompileArguments(input, output), runner.arguments[2])
        val compiled = result.outputs.single()
        assertEquals("x86_64-unknown-linux-gnu", compiled.normalizedTarget)
        assertEquals(WasmlineArtifactType.CWASM, compiled.artifact.type)
        assertEquals(WasmlineExecutionModel.COMPONENT_MODEL, compiled.artifact.executionModel)
        assertEquals(WasmlineInvocationProtocol.COMPONENT_EXPORT, compiled.artifact.invocationProtocol)
        assertEquals("wasmtime-47.0.2", compiled.artifact.targetCompilerVersion)
        assertEquals(FileDigest.sha256Hex(output), compiled.artifact.sha256)
        assertEquals(FileDigest.sha256Hex(input), result.inputComponentSha256)
        assertEquals(metadata.contractMetadata, compiled.artifact.contractMetadata)
    }

    @Test
    fun acceptsWasmtimeMinWhenCompileSubcommandIsAvailable() = withCompilerDirectory { root ->
        val compilerFile = executable(File(root, "wasmtime-min"))
        val input = File(root, "plugin-component.wasm").apply { writeBytes(byteArrayOf(1)) }
        val output = File(root, "plugin.cwasm")
        val runner = RecordingComponentCompilerRunner { arguments ->
            if (arguments.firstOrNull() == "compile" && arguments.getOrNull(1) != "--help") {
                output.writeBytes(byteArrayOf(2, 3))
            }
        }

        val result = ComponentCompiler(runner).compile(request(compilerFile, input, output))

        assertEquals(output, result.outputs.single().outputFile)
        assertEquals(listOf("--version"), runner.arguments[0])
        assertEquals(listOf("compile", "--help"), runner.arguments[1])
        assertEquals(3, runner.arguments.size)
    }

    @Test
    fun rejectsWasmtimeWithoutCompileSubcommand() = withCompilerDirectory { root ->
        val compilerFile = executable(File(root, "wasmtime-min"))
        val input = File(root, "plugin-component.wasm").apply { writeBytes(byteArrayOf(1)) }
        val runner = RecordingComponentCompilerRunner(
            exitCode = { arguments -> if (arguments == listOf("compile", "--help")) 2 else 0 },
        )

        val error = assertFailsWith<IllegalStateException> {
            ComponentCompiler(runner).compile(request(compilerFile, input, File(root, "plugin.cwasm")))
        }

        assertTrue(error.message.orEmpty().contains("does not provide the compile subcommand"))
        assertEquals(listOf(listOf("--version"), listOf("compile", "--help")), runner.arguments)
    }

    @Test
    fun compilesPulley32AndPulley64ToPortablePwasmArtifacts() = withCompilerDirectory { root ->
        val compilerFile = executable(File(root, "wasmtime"))
        val input = File(root, "plugin-component.wasm").apply { writeBytes(byteArrayOf(1)) }
        val outputs = listOf(
            File(root, "plugin-pulley32.pwasm"),
            File(root, "plugin-pulley64.pwasm"),
        )
        val runner = RecordingComponentCompilerRunner { arguments ->
            if (arguments.firstOrNull() == "compile" && arguments.getOrNull(1) != "--help") {
                File(arguments[arguments.indexOf("-o") + 1]).writeBytes(byteArrayOf(8, 9))
            }
        }

        val result = ComponentCompiler(runner).compile(
            ComponentAotCompileRequest(
                wasmtimeCompiler = compilerFile,
                inputComponent = input,
                targets = listOf(
                    ComponentAotTarget("pulley32-unknown-unknown-elf", ComponentAotBackend.PULLEY, outputs[0]),
                    ComponentAotTarget("pulley64", ComponentAotBackend.PULLEY, outputs[1]),
                ),
                wasmtimeVersion = "47.0.2",
            ),
        )

        assertEquals(listOf("pulley32", "pulley64"), result.outputs.map { it.artifact.targetCpu })
        assertTrue(result.outputs.all { it.artifact.type == WasmlineArtifactType.PWASM })
        assertTrue(result.outputs.all { it.artifact.executionModel == WasmlineExecutionModel.COMPONENT_MODEL })
        assertTrue(result.outputs.all { it.artifact.targetOs == null })
        assertFalse(result.outputs[0].artifact.is64Bit)
        assertTrue(result.outputs[1].artifact.is64Bit)
        assertEquals("pulley32-unknown-unknown-elf", result.outputs[0].normalizedTarget)
        assertEquals("pulley64", result.outputs[1].normalizedTarget)
        assertNull(result.outputs[0].artifact.targetOs)
    }

    @Test
    fun rejectsBackendAndTargetMismatchesBeforeRunningWasmtime() = withCompilerDirectory { root ->
        val compilerFile = executable(File(root, "wasmtime"))
        val input = File(root, "plugin-component.wasm").apply { writeBytes(byteArrayOf(1)) }
        val runner = RecordingComponentCompilerRunner()

        val pulleyError = assertFailsWith<IllegalArgumentException> {
            ComponentCompiler(runner).compile(
                ComponentAotCompileRequest(
                    wasmtimeCompiler = compilerFile,
                    inputComponent = input,
                    targets = listOf(ComponentAotTarget("x86_64-linux", ComponentAotBackend.PULLEY, File(root, "bad.pwasm"))),
                    wasmtimeVersion = "47.0.2",
                ),
            )
        }
        val craneliftError = assertFailsWith<IllegalArgumentException> {
            ComponentCompiler(runner).compile(
                request(compilerFile, input, File(root, "bad.cwasm"), target = "pulley64"),
            )
        }

        assertTrue(pulleyError.message.orEmpty().contains("pulley32 or pulley64"))
        assertTrue(craneliftError.message.orEmpty().contains("cannot use a Pulley target"))
        assertTrue(runner.arguments.isEmpty())
    }

    @Test
    fun failsWhenWasmtimeDoesNotProduceTheRequestedOutput() = withCompilerDirectory { root ->
        val compilerFile = executable(File(root, "wasmtime"))
        val input = File(root, "plugin-component.wasm").apply { writeBytes(byteArrayOf(1)) }

        val error = assertFailsWith<IllegalStateException> {
            ComponentCompiler(RecordingComponentCompilerRunner()).compile(
                request(compilerFile, input, File(root, "plugin.cwasm")),
            )
        }

        assertTrue(error.message.orEmpty().contains("without producing Component AOT output"))
    }

    @Test
    fun rejectsExactWasmtimeVersionMismatchBeforeCompileHelp() = withCompilerDirectory { root ->
        val compilerFile = executable(File(root, "wasmtime"))
        val input = File(root, "plugin-component.wasm").apply { writeBytes(byteArrayOf(1)) }
        val runner = RecordingComponentCompilerRunner(
            versionOutput = "wasmtime $INCOMPATIBLE_WASMTIME_VERSION",
        )

        val error = assertFailsWith<IllegalStateException> {
            ComponentCompiler(runner).compile(
                request(compilerFile, input, File(root, "plugin.cwasm")),
            )
        }

        assertTrue(error.message.orEmpty().contains("expected 47.0.2, actual $INCOMPATIBLE_WASMTIME_VERSION"))
        assertEquals(listOf(listOf("--version")), runner.arguments)
    }

    @Test
    fun rejectsNonZeroToolResultBeforeCheckingOutput() = withCompilerDirectory { root ->
        val compilerFile = executable(File(root, "wasmtime"))
        val input = File(root, "plugin-component.wasm").apply { writeBytes(byteArrayOf(1)) }
        val runner = RecordingComponentCompilerRunner(
            exitCode = { arguments -> if (arguments.firstOrNull() == "compile" && arguments.getOrNull(1) != "--help") 9 else 0 },
        )

        val error = assertFailsWith<IllegalStateException> {
            ComponentCompiler(runner).compile(
                request(compilerFile, input, File(root, "plugin.cwasm")),
            )
        }

        assertTrue(error.message.orEmpty().contains("exited with code 9"))
        assertEquals(3, runner.arguments.size)
    }

    @Test
    fun rejectsDuplicateTargetsAfterAliasNormalization() = withCompilerDirectory { root ->
        val compilerFile = executable(File(root, "wasmtime"))
        val input = File(root, "plugin-component.wasm").apply { writeBytes(byteArrayOf(1)) }
        val runner = RecordingComponentCompilerRunner()

        val error = assertFailsWith<IllegalArgumentException> {
            ComponentCompiler(runner).compile(
                ComponentAotCompileRequest(
                    wasmtimeCompiler = compilerFile,
                    inputComponent = input,
                    targets = listOf(
                        ComponentAotTarget("x86_64-linux", ComponentAotBackend.CRANELIFT, File(root, "alias.cwasm")),
                        ComponentAotTarget("x86_64-unknown-linux-gnu", ComponentAotBackend.CRANELIFT, File(root, "triple.cwasm")),
                    ),
                    wasmtimeVersion = "47.0.2",
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("Duplicate Component AOT target"))
        assertTrue(runner.arguments.isEmpty())
    }

    private fun request(compiler: File, input: File, output: File, target: String = "x86_64-linux"): ComponentAotCompileRequest =
        ComponentAotCompileRequest(
            wasmtimeCompiler = compiler,
            inputComponent = input,
            targets = listOf(ComponentAotTarget(target, ComponentAotBackend.CRANELIFT, output)),
            wasmtimeVersion = "47.0.2",
        )

    private fun expectedCompileArguments(input: File, output: File): List<String> = listOf(
        "compile",
        input.absolutePath,
        "-o",
        output.absolutePath,
        "--target",
        "x86_64-unknown-linux-gnu",
        "-W",
        "component-model=y",
        "-C",
        "collector=drc",
        "-W",
        "gc=y",
        "-W",
        "gc-support=y",
        "-W",
        "reference-types=y",
        "-W",
        "function-references=y",
        "-W",
        "exceptions=y",
        "-W",
        "threads=n",
        "-W",
        "simd=n",
        "-W",
        "relaxed-simd=n",
        "-W",
        "concurrency-support=y",
        "-W",
        "max-wasm-stack=524288",
        "-O",
        "memory-guard-size=0",
        "-O",
        "signals-based-traps=n",
        "-O",
        "opt-level=0",
        "-C",
        "cranelift-debug-verifier=no",
    )
}

private class RecordingComponentCompilerRunner(
    private val versionOutput: String = "wasmtime 47.0.2",
    private val exitCode: (List<String>) -> Int = { 0 },
    private val onRun: (List<String>) -> Unit = {},
) : ComponentCompilerToolRunner {
    val arguments = mutableListOf<List<String>>()

    override fun run(executable: File, arguments: List<String>): ToolExecutionResult {
        this.arguments += arguments
        onRun(arguments)
        val output = if (arguments == listOf("--version")) versionOutput else ""
        return ToolExecutionResult(listOf(executable.absolutePath) + arguments, exitCode(arguments), output)
    }
}

private fun executable(file: File): File = file.apply {
    writeText("test executable")
    check(setExecutable(true)) { "Unable to mark test compiler executable: $absolutePath" }
}

private inline fun withCompilerDirectory(block: (File) -> Unit) {
    val directory = createTempDirectory("wasmline-component-compiler-test").toFile()
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}
