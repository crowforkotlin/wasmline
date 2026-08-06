package crow.wasmline.cli

import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineArtifactType
import crow.wasmline.plugin.core.component.ComponentAotBuildRecord
import crow.wasmline.plugin.core.component.ComponentAotEngineOptions
import crow.wasmline.plugin.core.component.ComponentBuildRecord
import crow.wasmline.plugin.core.toolchain.FileDigest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ComponentAotCliSupportTest {
    @Test
    fun `resolves exact full compiler and returns only component aot artifacts`() = withCliAotDirectory { root ->
        val component = File(root, "component/plugin.component.wasm").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val rawRecord = rawRecord(component)
        val fullCompiler = File(root, "wasmtime/wasmtime").apply {
            parentFile.mkdirs()
            writeText("full")
        }
        var resolvedVersion: String? = null
        var pipelineCalls = 0
        val adapter = ComponentAotCliAdapter(
            compilerResolver = ComponentAotCompilerResolver { directory, version ->
                assertEquals(fullCompiler.parentFile, directory)
                resolvedVersion = version
                fullCompiler
            },
            pipelineRunner = ComponentAotPipelineRunner { capturedRaw, componentDirectory, request ->
                pipelineCalls += 1
                assertEquals(rawRecord, capturedRaw)
                assertEquals(component.parentFile, componentDirectory)
                assertEquals(fullCompiler, request.wasmtimeCompiler)
                val artifacts = request.targets.map { target ->
                    target.outputFile.writeBytes(target.target.encodeToByteArray())
                    val pulley = target.backend.artifactType == WasmlineArtifactType.PWASM
                    WasmlineArtifact(
                        type = target.backend.artifactType,
                        url = target.outputFile.name,
                        sha256 = FileDigest.sha256Hex(target.outputFile),
                        targetCpu = if (pulley) "pulley64" else "x86_64",
                        targetOs = if (pulley) null else "linux",
                        targetCompilerVersion = "wasmtime-${request.wasmtimeVersion}",
                        is64Bit = true,
                        executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
                        invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
                        exportName = rawRecord.exportName,
                        contractMetadata = rawRecord.toArtifact(component.parentFile).contractMetadata,
                    )
                }
                ComponentAotBuildRecord(
                    rawComponent = rawRecord,
                    inputComponentSha256 = rawRecord.componentSha256,
                    wasmtimeVersion = request.wasmtimeVersion,
                    engineOptions = ComponentAotEngineOptions(),
                    artifacts = artifacts,
                )
            },
        )

        val result = adapter.compile(
            ComponentAotCliRequest(
                rawComponent = rawRecord,
                componentDirectory = component.parentFile,
                outputDirectory = File(root, "aot"),
                productName = "plugin",
                wasmtimeDirectory = fullCompiler.parentFile,
                targets = listOf("x86_64-linux", "pulley64"),
                wasmtimeVersion = "47.0.2",
            ),
        )

        assertEquals("47.0.2", resolvedVersion)
        assertEquals(1, pipelineCalls)
        assertEquals(listOf(WasmlineArtifactType.CWASM, WasmlineArtifactType.PWASM), result.artifacts.map { it.type })
        assertTrue(result.artifacts.all { it.executionModel == WasmlineExecutionModel.COMPONENT_MODEL })
        assertTrue(result.artifacts.none { it.type == WasmlineArtifactType.COMPONENT_WASM })
    }

    @Test
    fun `rejects component aot when no full compiler matches`() = withCliAotDirectory { root ->
        val component = File(root, "plugin.component.wasm").apply { writeBytes(byteArrayOf(1)) }
        var pipelineCalls = 0
        val adapter = ComponentAotCliAdapter(
            compilerResolver = ComponentAotCompilerResolver { _, _ -> null },
            pipelineRunner = ComponentAotPipelineRunner { _, _, _ ->
                pipelineCalls += 1
                error("pipeline must not run")
            },
        )

        val error = assertFailsWith<IllegalStateException> {
            adapter.compile(
                ComponentAotCliRequest(
                    rawComponent = rawRecord(component),
                    componentDirectory = root,
                    outputDirectory = File(root, "aot"),
                    productName = "plugin",
                    wasmtimeDirectory = File(root, "wasmtime-min"),
                    targets = listOf("pulley64"),
                    wasmtimeVersion = "47.0.2",
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("full Wasmtime 47.0.2"))
        assertTrue(error.message.orEmpty().contains("wasmtime-min is runtime-only"))
        assertEquals(0, pipelineCalls)
    }

    private fun rawRecord(component: File): ComponentBuildRecord = ComponentBuildRecord(
        componentFile = component.name,
        embeddedFile = "plugin.embedded.wasm",
        exportName = "plugin/invoke",
        componentSha256 = FileDigest.sha256Hex(component),
        witSha256 = "a".repeat(64),
        wasmToolsVersion = "1.255.0",
    )
}

private inline fun withCliAotDirectory(block: (File) -> Unit) {
    val directory = createTempDirectory("wasmline-cli-component-aot-test").toFile()
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}
