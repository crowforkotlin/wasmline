package crow.wasmline.plugin.core.component

import crow.wasmline.loader.model.WasmlineArtifactType
import crow.wasmline.plugin.core.toolchain.ToolExecutionResult
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComponentPipelineTest {
    @Test
    fun executesComponentStagesInOrder() = withPipelineDirectory { root ->
        val core = File(root, "plugin.wasm").apply { writeBytes(byteArrayOf(0, 97, 115, 109)) }
        val wit = File(root, "wit").apply { mkdirs() }
        File(wit, "world.wit").writeText("package test:plugin; world plugin {}")
        val adapter = File(root, "adapter.wasm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val tools = RecordingWasmTools()

        val result = ComponentPipeline(tools).componentize(
            ComponentizeRequest(
                coreWasm = core,
                witPath = wit,
                wasiPreview1Adapter = adapter,
                outputDirectory = File(root, "output"),
                productName = "plugin",
                world = "plugin",
            ),
        )

        assertEquals(listOf("version", "embed", "new", "validate", "inspect"), tools.operations)
        assertTrue(result.componentWasm.isFile)
        assertTrue(result.inspectedWit?.isFile == true)
        assertEquals(WasmlineArtifactType.COMPONENT_WASM, result.toArtifact().type)
        assertEquals("plugin/invoke", result.toArtifact().exportName)
        assertEquals("protobuf", result.toArtifact().contractMetadata["wasmline.rpc.codec"])
    }

    @Test
    fun describesExistingComponentWithoutComponentizingAgain() = withPipelineDirectory { root ->
        val component = File(root, "plugin.component.wasm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val wit = File(root, "world.wit").apply { writeText("package test:plugin; world plugin {}") }
        val tools = RecordingWasmTools()

        val result = ComponentPipeline(tools).describeExisting(
            ExistingComponentRequest(
                componentWasm = component,
                witPath = wit,
                outputDirectory = File(root, "output"),
                productName = "plugin",
            ),
        )

        assertEquals(listOf("version", "validate", "inspect"), tools.operations)
        assertEquals("plugin-component.wasm", result.componentWasm.name)
        assertEquals("plugin/invoke", result.exportName)
    }
}

private class RecordingWasmTools : WasmTools {
    val operations = mutableListOf<String>()

    override fun version(): String {
        operations += "version"
        return "wasm-tools 1.255.0"
    }

    override fun embedWit(witPath: File, inputWasm: File, outputWasm: File, world: String?): ToolExecutionResult {
        operations += "embed"
        outputWasm.writeBytes(inputWasm.readBytes())
        return success()
    }

    override fun createComponent(
        embeddedWasm: File,
        adapter: File,
        outputComponent: File,
        adapterModule: String,
    ): ToolExecutionResult {
        operations += "new"
        outputComponent.writeBytes(embeddedWasm.readBytes() + adapter.readBytes())
        return success()
    }

    override fun validate(component: File): ToolExecutionResult {
        operations += "validate"
        return success()
    }

    override fun inspectWit(component: File): String {
        operations += "inspect"
        return "package test:plugin; world plugin {}"
    }

    private fun success(): ToolExecutionResult = ToolExecutionResult(emptyList(), 0, "")
}

private inline fun withPipelineDirectory(block: (File) -> Unit) {
    val directory = createTempDirectory("wasmline-component-test").toFile()
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}
