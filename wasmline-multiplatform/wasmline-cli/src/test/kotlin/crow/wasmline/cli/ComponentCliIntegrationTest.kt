package crow.wasmline.cli

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineComponentServiceContract
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.SignedManifestEnvelope
import crow.wasmline.loader.model.WasmlineManifest
import crow.wasmline.plugin.core.aot.WasmlineAotBuildRecord
import crow.wasmline.plugin.core.aot.WasmlineAotBuildRecords
import crow.wasmline.plugin.core.component.ComponentBuildRecords
import crow.wasmline.plugin.core.toolchain.ExternalToolRunner
import crow.wasmline.plugin.core.toolchain.ToolchainCatalog
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the complete catalog-backed Component CLI workflow with pinned external tools.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@OptIn(ExperimentalSerializationApi::class)
class ComponentCliIntegrationTest {
    @Test
    fun runsComponentCommandsWithPinnedReleaseTools() {
        if (System.getenv(LIVE_TESTS_ENV) != "1") return

        val cli = requireExecutable(CLI_ENV)
        val witBindgen = requireExecutable(WIT_BINDGEN_ENV)
        val wasmTools = requireExecutable(WASM_TOOLS_ENV)
        val adapter = requireFile(WASI_ADAPTER_ENV)
        val witDirectory = resolveCanonicalWitDirectory()
        val root = createTempDirectory("wasmline-cli-live").toFile()
        try {
            val compilerCache = File(root, "compiler-cache")
            val coreWasm = File(root, "dummy-core.wasm")
            ExternalToolRunner().run(
                executable = wasmTools,
                arguments = listOf(
                    "component",
                    "embed",
                    "--world",
                    "plugin",
                    "--dummy",
                    witDirectory.absolutePath,
                    "-o",
                    coreWasm.absolutePath,
                ),
            )

            val generatedDirectory = File(root, "generated")
            runCli(
                cli,
                "wit",
                "generate",
                "--wit",
                witDirectory.absolutePath,
                "--world",
                "plugin",
                "--wit-bindgen",
                witBindgen.absolutePath,
                "--output",
                generatedDirectory.absolutePath,
            ).assertSuccessWithoutToolHelp("Kotlin bindings written to:")
            assertTrue(generatedDirectory.walkTopDown().any { it.isFile && it.extension == "kt" })

            val componentDirectory = File(root, "component")
            runCli(
                cli,
                "componentize",
                "--input",
                coreWasm.absolutePath,
                "--wit",
                witDirectory.absolutePath,
                "--world",
                "plugin",
                "--invocation-protocol",
                "WASMLINE_SERVICE",
                "--adapter",
                adapter.absolutePath,
                "--wasm-tools",
                wasmTools.absolutePath,
                "--output",
                componentDirectory.absolutePath,
                "--name",
                "cli-live",
            ).assertSuccessWithoutToolHelp("Component Wasm:")

            val component = File(componentDirectory, "cli-live-component.wasm")
            assertTrue(component.isFile)
            assertTrue(File(componentDirectory, ComponentBuildRecords.FILE_NAME).isFile)

            runCli(
                cli,
                "component",
                "validate",
                "--input",
                component.absolutePath,
                "--wasm-tools",
                wasmTools.absolutePath,
            ).assertSuccessWithoutToolHelp("Valid Component Wasm:")

            val inspect = runCli(
                cli,
                "component",
                "inspect",
                "--input",
                component.absolutePath,
                "--wasm-tools",
                wasmTools.absolutePath,
            )
            assertEquals(0, inspect.exitCode, inspect.output)
            assertTrue(inspect.output.contains("import host:"))
            assertTrue(inspect.output.contains("export plugin:"))

            val mismatch = runCli(
                cli,
                "component",
                "validate",
                "--input",
                component.absolutePath,
                "--wasm-tools",
                wasmTools.absolutePath,
                "--version",
                "0.0.0",
            )
            assertEquals(1, mismatch.exitCode, mismatch.output)
            assertTrue(mismatch.output.contains("version mismatch"))
            assertTrue(
                mismatch.output.contains(
                    "expected 0.0.0, actual ${ToolchainCatalog.WASM_TOOLS_VERSION}",
                ),
            )

            val keyDirectory = File(root, "keys")
            runCliIn(
                cli,
                root,
                "generate-key-pair",
                "--save",
                "--output",
                keyDirectory.absolutePath,
            ).assertSuccessWithoutToolHelp("Private key saved to:")
            val privateKey = File(keyDirectory, "ed25519_private.key")
            assertTrue(privateKey.isFile)

            val compileRoot = File(root, "compiled")
            val compileInvocation = runCli(
                cli,
                "compile",
                "--input",
                component.absolutePath,
                "--name",
                "cli-compile",
                "--output",
                compileRoot.absolutePath,
                "--execution-model",
                "COMPONENT_MODEL",
                "--invocation-protocol",
                "WASMLINE_SERVICE",
                "--raw-component",
                "--wit",
                witDirectory.absolutePath,
                "--world",
                "plugin",
                "--wasm-tools",
                wasmTools.absolutePath,
                "--aot-compiler-cache",
                compilerCache.absolutePath,
                "--auto-download",
                "--target",
                "x86_64-linux",
                "--target",
                "pulley64",
            )
            compileInvocation.assertSuccessWithoutToolHelp("AOT build record written to:")
            assertComponentAotDiagnostics(compileInvocation.output)
            val compileDirectory = File(compileRoot, "cli-compile-1.0.0")
            val compileResult = WasmlineAotBuildRecords.read(
                File(compileDirectory, WasmlineAotBuildRecords.FILE_NAME),
            )
            assertComponentAotArtifacts(compileResult)

            val buildInvocation = runCliIn(
                cli,
                root,
                "build",
                "--input",
                component.absolutePath,
                "--name",
                "cli-package",
                "--plugin-id",
                "crow.wasmline.test.component",
                "--version",
                "1.0.0",
                "--execution-model",
                "COMPONENT_MODEL",
                "--invocation-protocol",
                "WASMLINE_SERVICE",
                "--raw-component",
                "--wit",
                witDirectory.absolutePath,
                "--world",
                "plugin",
                "--wasm-tools",
                wasmTools.absolutePath,
                "--aot-compiler-cache",
                compilerCache.absolutePath,
                "--target",
                "x86_64-linux",
                "--target",
                "pulley64",
                "--key",
                privateKey.absolutePath,
            )
            buildInvocation.assertSuccessWithoutToolHelp("Package written to:")
            assertComponentAotDiagnostics(buildInvocation.output)

            val folderName = "crow.wasmline.test.component-1.0.0"
            val packageDirectory = File(root, "build/wasmline/output/$folderName")
            val manifest = File(packageDirectory, "manifest.wlm")
            val archive = File(root, "build/wasmline/dist/$folderName.zip")
            assertTrue(manifest.isFile)
            assertTrue(archive.isFile)

            val envelope = ProtoBuf.decodeFromByteArray(
                SignedManifestEnvelope.serializer(),
                manifest.readBytes(),
            )
            val manifestPayload = ProtoBuf.decodeFromByteArray(WasmlineManifest.serializer(), envelope.payload)
            assertEquals(compileResult.runtimeContract, manifestPayload.runtimeContract)
            assertEquals(compileResult.artifactTargets, manifestPayload.artifactTargets)

            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().map { it.name }.toSet()
                assertTrue("$folderName/manifest.wlm" in entries)
                assertTrue("$folderName/debug/manifest.json" in entries)
                assertTrue("$folderName/debug/${WasmlineAotBuildRecords.FILE_NAME}" in entries)
                assertTrue("$folderName/debug/artifact-index.json" in entries)
                manifestPayload.artifactTargets.flatMap { target ->
                    target.variants.map { variant ->
                        val extension = when (target.format) {
                            WasmlineArtifactFormat.RAW_WASM -> "wasm"
                            WasmlineArtifactFormat.CWASM -> "cwasm"
                            WasmlineArtifactFormat.PWASM -> "pwasm"
                        }
                        "$folderName/artifacts/sha256/${variant.sha256.take(2)}/${variant.sha256}.$extension"
                    }
                }.forEach { artifactEntry -> assertTrue(artifactEntry in entries, artifactEntry) }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun CliResult.assertSuccessWithoutToolHelp(expectedOutput: String) {
        assertEquals(0, exitCode, output)
        assertTrue(output.contains(expectedOutput), output)
        assertFalse(output.contains("Usage: wit-bindgen"), output)
        assertFalse(output.contains("Usage: wasm-tools"), output)
        assertFalse(output.contains("package root:component"), output)
    }

    private fun assertComponentAotArtifacts(record: WasmlineAotBuildRecord) {
        assertEquals(
            setOf(WasmlineArtifactFormat.CWASM, WasmlineArtifactFormat.PWASM),
            record.compiledOutputs.map { it.format }.toSet(),
        )
        assertEquals(WasmlineExecutionModel.COMPONENT_MODEL, record.runtimeContract.executionModel)
        assertEquals(WasmlineInvocationProtocol.WASMLINE_SERVICE, record.runtimeContract.invocationProtocol)
        assertEquals(WasmlineComponentServiceContract.DEFAULT_EXPORT, record.runtimeContract.exportName)
        assertEquals(
            WasmlineComponentServiceContract.DEFAULT_CODEC,
            record.runtimeContract.contractMetadata[WasmlineComponentServiceContract.METADATA_CODEC],
        )
        assertEquals(
            WasmlineComponentServiceContract.VERSION,
            record.runtimeContract.contractMetadata[WasmlineComponentServiceContract.METADATA_VERSION],
        )
        assertTrue(record.compiledOutputs.all { it.aotCompatibilityProfileId != null })
        assertTrue(record.compiledOutputs.all { it.contentRelativePath.startsWith("artifacts/sha256/") })
    }

    private fun assertComponentAotDiagnostics(output: String) {
        assertTrue(
            output.contains(
                "format=CWASM executionModel=COMPONENT_MODEL backend=CRANELIFT target=x86_64-linux",
            ),
            output,
        )
        assertTrue(
            output.contains(
                "format=PWASM executionModel=COMPONENT_MODEL backend=PULLEY target=pulley64",
            ),
            output,
        )
    }

    private fun runCli(cli: File, vararg arguments: String): CliResult = runCliIn(cli, null, *arguments)

    private fun runCliIn(cli: File, workingDirectory: File?, vararg arguments: String): CliResult {
        val process = ProcessBuilder(listOf(cli.absolutePath) + arguments)
            .redirectErrorStream(true)
            .apply { workingDirectory?.let { directory(it) } }
            .start()
        var output = ""
        val reader = thread(name = "wasmline-cli-live-output", isDaemon = true) {
            output = process.inputStream.bufferedReader().use { it.readText() }
        }
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor()
        }
        reader.join()
        check(finished) {
            "CLI command timed out: ${arguments.joinToString(" ")}"
        }
        return CliResult(process.exitValue(), output)
    }

    private fun resolveCanonicalWitDirectory(): File {
        val configured = System.getenv(WIT_DIRECTORY_ENV)?.let(::File)
        val candidates = listOfNotNull(
            configured,
            File("../wasmline-plugin-core/src/main/resources/$CANONICAL_WIT_PATH"),
            File("wasmline-plugin-core/src/main/resources/$CANONICAL_WIT_PATH"),
            File("wasmline-multiplatform/wasmline-plugin-core/src/main/resources/$CANONICAL_WIT_PATH"),
        )
        return candidates.firstOrNull(File::isDirectory)?.canonicalFile
            ?: error("Unable to locate the canonical Wasmline Service WIT directory.")
    }

    private fun requireExecutable(name: String): File = requireFile(name).also { file ->
        require(file.canExecute()) { "$name is not executable: ${file.absolutePath}" }
    }

    private fun requireFile(name: String): File {
        val path = requireNotNull(System.getenv(name)) {
            "$name must be set when $LIVE_TESTS_ENV=1."
        }
        return File(path).also { file ->
            require(file.isFile) { "$name does not point to a file: ${file.absolutePath}" }
        }
    }

    private data class CliResult(val exitCode: Int, val output: String)

    private companion object {
        const val LIVE_TESTS_ENV = "WASMLINE_LIVE_TESTS"
        const val CLI_ENV = "WASMLINE_TEST_CLI"
        const val WIT_BINDGEN_ENV = "WASMLINE_TEST_WIT_BINDGEN"
        const val WASM_TOOLS_ENV = "WASMLINE_TEST_WASM_TOOLS"
        const val WASI_ADAPTER_ENV = "WASMLINE_TEST_WASI_ADAPTER"
        const val WIT_DIRECTORY_ENV = "WASMLINE_TEST_WIT_DIRECTORY"
        const val CANONICAL_WIT_PATH = "META-INF/wasmline/wit/wasmline-service"
    }
}
