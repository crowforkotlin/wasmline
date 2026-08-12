package crow.wasmline.plugin.core.component

import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.plugin.core.compiler.WasmtimeCompiler
import crow.wasmline.plugin.core.toolchain.FileDigest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ComponentAotPipelineTest {
    @Test
    fun chainsFinishedComponentThroughRawAndAotRecords() = withComponentAotPipelineDirectory { root ->
        val componentDirectory = File(root, "component").apply { mkdirs() }
        val component = File(componentDirectory, "plugin-component.wasm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val embedded = File(componentDirectory, "plugin-embedded.wasm").apply { writeBytes(byteArrayOf(4)) }
        val inspectedWit = File(componentDirectory, "plugin-component.wit").apply { writeText("world plugin {}") }
        val componentResult = ComponentizeResult(
            coreWasm = embedded,
            embeddedWasm = embedded,
            componentWasm = component,
            inspectedWit = inspectedWit,
            world = "plugin",
            invocationProtocol = WasmlineInvocationProtocol.WASMLINE_SERVICE,
            exportName = "plugin/invoke",
            codec = "protobuf",
            serviceProtocolVersion = "1",
            componentSha256 = FileDigest.sha256Hex(component),
            witSha256 = "a".repeat(64),
            adapterSha256 = "b".repeat(64),
            adapterVersion = "47.0.2",
            witBindgenVersion = "0.57.1",
            wasmToolsVersion = "1.255.0",
        )
        val aotDirectory = File(root, "aot")
        val targets = listOf(
            ComponentAotTarget("x86_64-linux", ComponentAotBackend.CRANELIFT, File(aotDirectory, "plugin.cwasm")),
            ComponentAotTarget("pulley64", ComponentAotBackend.PULLEY, File(aotDirectory, "plugin.pwasm")),
        )
        var capturedRequest: ComponentAotCompileRequest? = null
        val pipeline = ComponentAotPipeline { request ->
            capturedRequest = request
            fakeCompile(request)
        }
        val componentRecordFile = File(componentDirectory, ComponentBuildRecords.FILE_NAME)
        val aotRecordFile = File(aotDirectory, ComponentAotBuildRecords.FILE_NAME)

        val result = pipeline.compile(
            componentResult,
            componentRecordFile,
            ComponentAotPipelineRequest(
                wasmtimeCompiler = File(root, "wasmtime"),
                wasmtimeVersion = "47.0.2",
                targets = targets,
                outputRecord = aotRecordFile,
            ),
        )

        val request = requireNotNull(capturedRequest)
        assertEquals(component.canonicalFile, request.inputComponent.canonicalFile)
        assertEquals("plugin/invoke", request.artifactMetadata.exportName)
        assertEquals("protobuf", request.artifactMetadata.contractMetadata["wasmline.service.codec"])
        assertTrue(componentRecordFile.isFile)
        assertTrue(aotRecordFile.isFile)
        assertEquals(result.rawComponent, ComponentBuildRecords.read(componentRecordFile))
        assertEquals(result.aotRecord, ComponentAotBuildRecords.read(aotRecordFile))
        assertEquals(targets.map { it.backend.artifactType }, result.aotRecord.artifacts.map { it.type })
    }

    @Test
    fun compilesAnExistingRawRecordWithoutRebuildingTheComponent() = withComponentAotPipelineDirectory { root ->
        val component = File(root, "plugin-component.wasm").apply { writeBytes(byteArrayOf(1)) }
        val rawRecord = ComponentBuildRecord(
            componentFile = component.name,
            embeddedFile = "plugin-embedded.wasm",
            componentSha256 = FileDigest.sha256Hex(component),
            witSha256 = "a".repeat(64),
            wasmToolsVersion = "1.255.0",
        )
        var compileCalls = 0
        val pipeline = ComponentAotPipeline { request ->
            compileCalls += 1
            fakeCompile(request)
        }
        val aotDirectory = File(root, "aot")

        pipeline.compile(
            rawRecord,
            root,
            ComponentAotPipelineRequest(
                wasmtimeCompiler = File(root, "wasmtime"),
                wasmtimeVersion = "47.0.2",
                targets = listOf(
                    ComponentAotTarget("pulley64", ComponentAotBackend.PULLEY, File(aotDirectory, "plugin.pwasm")),
                ),
                outputRecord = File(aotDirectory, ComponentAotBuildRecords.FILE_NAME),
            ),
        )

        assertEquals(1, compileCalls)
    }

    @Test
    fun rejectsEmptyNativeTargetsAndOutputsOutsideTheRecordDirectory() = withComponentAotPipelineDirectory { root ->
        assertFailsWith<IllegalArgumentException> {
            ComponentAotPipelineRequest(
                wasmtimeCompiler = File(root, "wasmtime"),
                wasmtimeVersion = "47.0.2",
                targets = emptyList(),
                outputRecord = File(root, "aot/${ComponentAotBuildRecords.FILE_NAME}"),
            )
        }

        val component = File(root, "plugin-component.wasm").apply { writeBytes(byteArrayOf(1)) }
        val rawRecord = ComponentBuildRecord(
            componentFile = component.name,
            embeddedFile = component.name,
            componentSha256 = FileDigest.sha256Hex(component),
            witSha256 = "a".repeat(64),
            wasmToolsVersion = "1.255.0",
        )
        var compileCalls = 0
        val pipeline = ComponentAotPipeline { request ->
            compileCalls += 1
            fakeCompile(request)
        }

        assertFailsWith<IllegalArgumentException> {
            pipeline.compile(
                rawRecord,
                root,
                ComponentAotPipelineRequest(
                    wasmtimeCompiler = File(root, "wasmtime"),
                    wasmtimeVersion = "47.0.2",
                    targets = listOf(
                        ComponentAotTarget("pulley64", ComponentAotBackend.PULLEY, File(root, "outside.pwasm")),
                    ),
                    outputRecord = File(root, "aot/${ComponentAotBuildRecords.FILE_NAME}"),
                ),
            )
        }
        assertEquals(0, compileCalls)
    }

    private fun fakeCompile(request: ComponentAotCompileRequest): ComponentAotCompileResult {
        val outputs = request.targets.map { target ->
            target.outputFile.parentFile.mkdirs()
            target.outputFile.writeBytes(target.target.encodeToByteArray())
            val normalizedTarget = WasmtimeCompiler.normalizeTarget(target.target)
            val (targetCpu, targetOs) = WasmtimeCompiler().parseTarget(normalizedTarget)
            ComponentAotCompileOutput(
                requestedTarget = target.target,
                normalizedTarget = normalizedTarget,
                backend = target.backend,
                outputFile = target.outputFile,
                artifact = WasmlineArtifact(
                    type = target.backend.artifactType,
                    url = target.outputFile.name,
                    sha256 = FileDigest.sha256Hex(target.outputFile),
                    targetCpu = targetCpu,
                    targetOs = targetOs,
                    targetCompilerVersion = "wasmtime-${request.wasmtimeVersion}",
                    is64Bit = targetCpu.contains("64"),
                    executionModel = request.artifactMetadata.executionModel,
                    invocationProtocol = request.artifactMetadata.invocationProtocol,
                    exportName = request.artifactMetadata.exportName,
                    contractMetadata = request.artifactMetadata.contractMetadata,
                ),
            )
        }
        return ComponentAotCompileResult(
            inputComponent = request.inputComponent,
            inputComponentSha256 = FileDigest.sha256Hex(request.inputComponent),
            wasmtimeVersion = request.wasmtimeVersion,
            engineOptions = request.engineOptions,
            artifactMetadata = request.artifactMetadata,
            outputs = outputs,
        )
    }
}

private inline fun withComponentAotPipelineDirectory(block: (File) -> Unit) {
    val directory = createTempDirectory("wasmline-component-aot-pipeline-test").toFile()
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}
