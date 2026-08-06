package crow.wasmline.plugin.core.component

import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineArtifactType
import crow.wasmline.plugin.core.toolchain.FileDigest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComponentAotBuildRecordTest {
    @Test
    fun persistsRawMetadataAndBothNativePhysicalFormats() = withAotRecordDirectory { root ->
        val rawFile = File(root, "plugin-component.wasm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val cwasm = File(root, "native/plugin-linux.cwasm").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(4, 5))
        }
        val pwasm = File(root, "native/plugin-pulley64.pwasm").apply { writeBytes(byteArrayOf(6, 7)) }
        val rawRecord = rawRecord(rawFile)
        val result = compileResult(rawFile, cwasm, pwasm)
        val recordFile = File(root, ComponentAotBuildRecords.FILE_NAME)

        val written = ComponentAotBuildRecords.write(rawRecord, result, recordFile)
        val restored = ComponentAotBuildRecords.read(recordFile)

        assertEquals(written, restored)
        assertEquals(rawRecord, restored.rawComponent)
        assertEquals(listOf(WasmlineArtifactType.CWASM, WasmlineArtifactType.PWASM), restored.artifacts.map { it.type })
        assertTrue(restored.artifacts.all { it.executionModel == WasmlineExecutionModel.COMPONENT_MODEL })
        assertEquals(listOf("native/plugin-linux.cwasm", "native/plugin-pulley64.pwasm"), restored.artifacts.map { it.url })
        assertEquals(listOf(cwasm.canonicalFile, pwasm.canonicalFile), restored.resolveArtifacts(root).map { it.file.canonicalFile })
    }

    @Test
    fun detectsTamperedAotFilesWhenResolvingTheRecord() = withAotRecordDirectory { root ->
        val rawFile = File(root, "plugin-component.wasm").apply { writeBytes(byteArrayOf(1)) }
        val cwasm = File(root, "plugin.cwasm").apply { writeBytes(byteArrayOf(2)) }
        val pwasm = File(root, "plugin.pwasm").apply { writeBytes(byteArrayOf(3)) }
        val record = ComponentAotBuildRecords.write(
            rawRecord(rawFile),
            compileResult(rawFile, cwasm, pwasm),
            File(root, ComponentAotBuildRecords.FILE_NAME),
        )
        cwasm.writeBytes(byteArrayOf(9))

        val error = assertFailsWith<IllegalArgumentException> { record.resolveArtifacts(root) }

        assertTrue(error.message.orEmpty().contains("SHA-256 mismatch"))
    }

    @Test
    fun rejectsRawOrCoreArtifactsInAnAotRecord() = withAotRecordDirectory { root ->
        val rawFile = File(root, "plugin-component.wasm").apply { writeBytes(byteArrayOf(1)) }
        val rawRecord = rawRecord(rawFile)
        val componentArtifact = artifact(
            type = WasmlineArtifactType.COMPONENT_WASM,
            url = "plugin-component.wasm",
            bytesFile = rawFile,
        )
        val coreArtifact = artifact(
            type = WasmlineArtifactType.CWASM,
            url = "plugin.cwasm",
            bytesFile = rawFile,
        ).copy(
            executionModel = WasmlineExecutionModel.CORE_WASM,
            invocationProtocol = WasmlineInvocationProtocol.WASMLINE_CORE,
        )

        assertFailsWith<IllegalArgumentException> { buildRecord(rawRecord, componentArtifact) }
        assertFailsWith<IllegalArgumentException> { buildRecord(rawRecord, coreArtifact) }
    }

    @Test
    fun materializesOnlyVerifiedNativeArtifactsIntoThePackage() = withAotRecordDirectory { root ->
        val rawFile = File(root, "plugin-component.wasm").apply { writeBytes(byteArrayOf(1)) }
        val nativeDirectory = File(root, "aot/native").apply { mkdirs() }
        val cwasm = File(nativeDirectory, "plugin.cwasm").apply { writeBytes(byteArrayOf(2)) }
        val pwasm = File(nativeDirectory, "plugin.pwasm").apply { writeBytes(byteArrayOf(3)) }
        val aotDirectory = File(root, "aot")
        val record = ComponentAotBuildRecords.write(
            rawRecord(rawFile),
            compileResult(rawFile, cwasm, pwasm),
            File(aotDirectory, ComponentAotBuildRecords.FILE_NAME),
        )
        val packageDirectory = File(root, "package")

        val artifacts = ComponentAotBuildRecords.materializeArtifacts(record, aotDirectory, packageDirectory)

        assertEquals(listOf("plugin.cwasm", "plugin.pwasm"), artifacts.map { it.url })
        assertTrue(File(packageDirectory, "plugin.cwasm").isFile)
        assertTrue(File(packageDirectory, "plugin.pwasm").isFile)
        assertFalse(File(packageDirectory, rawFile.name).exists())
        assertTrue(artifacts.all { it.executionModel == WasmlineExecutionModel.COMPONENT_MODEL })
    }

    @Test
    fun rejectsIosCwasmEvenWhenItsComponentMetadataIsValid() = withAotRecordDirectory { root ->
        val rawFile = File(root, "plugin-component.wasm").apply { writeBytes(byteArrayOf(1)) }
        val iosCwasm = artifact(
            type = WasmlineArtifactType.CWASM,
            url = "plugin-ios.cwasm",
            bytesFile = rawFile,
        ).copy(targetCpu = "aarch64", targetOs = "ios")

        val error = assertFailsWith<IllegalArgumentException> {
            buildRecord(rawRecord(rawFile), iosCwasm)
        }

        assertTrue(error.message.orEmpty().contains("portable PWASM"))
    }

    private fun compileResult(rawFile: File, cwasm: File, pwasm: File): ComponentAotCompileResult {
        val metadata = ComponentAotArtifactMetadata(exportName = "plugin/invoke")
        return ComponentAotCompileResult(
            inputComponent = rawFile,
            inputComponentSha256 = FileDigest.sha256Hex(rawFile),
            wasmtimeVersion = "47.0.2",
            engineOptions = ComponentAotEngineOptions(),
            artifactMetadata = metadata,
            outputs = listOf(
                output(ComponentAotBackend.CRANELIFT, "x86_64-linux", "x86_64-unknown-linux-gnu", cwasm, metadata),
                output(ComponentAotBackend.PULLEY, "pulley64", "pulley64", pwasm, metadata),
            ),
        )
    }

    private fun output(
        backend: ComponentAotBackend,
        requestedTarget: String,
        normalizedTarget: String,
        file: File,
        metadata: ComponentAotArtifactMetadata,
    ): ComponentAotCompileOutput = ComponentAotCompileOutput(
        requestedTarget = requestedTarget,
        normalizedTarget = normalizedTarget,
        backend = backend,
        outputFile = file,
        artifact = artifact(backend.artifactType, file.name, file, metadata),
    )

    private fun artifact(
        type: WasmlineArtifactType,
        url: String,
        bytesFile: File,
        metadata: ComponentAotArtifactMetadata = ComponentAotArtifactMetadata(),
    ): WasmlineArtifact = WasmlineArtifact(
        type = type,
        url = url,
        sha256 = FileDigest.sha256Hex(bytesFile),
        targetCpu = if (type == WasmlineArtifactType.PWASM) "pulley64" else "x86_64",
        targetOs = if (type == WasmlineArtifactType.PWASM) null else "linux",
        targetCompilerVersion = "wasmtime-47.0.2",
        executionModel = metadata.executionModel,
        invocationProtocol = metadata.invocationProtocol,
        exportName = metadata.exportName,
        contractMetadata = metadata.contractMetadata,
    )

    private fun rawRecord(rawFile: File): ComponentBuildRecord = ComponentBuildRecord(
        componentFile = rawFile.name,
        embeddedFile = "plugin-embedded.wasm",
        inspectedWitFile = "plugin-component.wit",
        world = "plugin",
        componentSha256 = FileDigest.sha256Hex(rawFile),
        witSha256 = "a".repeat(64),
        wasmToolsVersion = "1.255.0",
    )

    private fun buildRecord(rawRecord: ComponentBuildRecord, artifact: WasmlineArtifact): ComponentAotBuildRecord = ComponentAotBuildRecord(
        rawComponent = rawRecord,
        inputComponentSha256 = rawRecord.componentSha256,
        wasmtimeVersion = "47.0.2",
        engineOptions = ComponentAotEngineOptions(),
        artifacts = listOf(artifact),
    )
}

private inline fun withAotRecordDirectory(block: (File) -> Unit) {
    val directory = createTempDirectory("wasmline-component-aot-record-test").toFile()
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}
