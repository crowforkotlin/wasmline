package crow.wasmline.plugin.core.component

import crow.wasmline.WasmlineComponentServiceContract
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.WasmlineTypedComponentContract
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
        assertEquals(WasmlineInvocationProtocol.COMPONENT_EXPORT, result.invocationProtocol)
        assertEquals(null, result.toArtifact().exportName)
        assertEquals("test:plugin", result.toArtifact().contractMetadata[WasmlineTypedComponentContract.METADATA_WIT_PACKAGE])
        assertEquals(null, result.toArtifact().contractMetadata[WasmlineComponentServiceContract.METADATA_CODEC])
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
        assertEquals(WasmlineInvocationProtocol.COMPONENT_EXPORT, result.invocationProtocol)
        assertEquals(null, result.exportName)
    }

    @Test
    fun recordsComponentServiceMetadataOnlyForTheWasmlineServiceProtocol() = withPipelineDirectory { root ->
        val component = File(root, "plugin.component.wasm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val result = ComponentPipeline(RecordingWasmTools()).describeExisting(
            ExistingComponentRequest(
                componentWasm = component,
                outputDirectory = File(root, "output"),
                productName = "plugin",
                invocationProtocol = WasmlineInvocationProtocol.WASMLINE_SERVICE,
                exportName = WasmlineComponentServiceContract.DEFAULT_EXPORT,
                codec = WasmlineComponentServiceContract.DEFAULT_CODEC,
                serviceProtocolVersion = WasmlineComponentServiceContract.VERSION,
            ),
        )

        val artifact = result.toArtifact()
        assertEquals(WasmlineInvocationProtocol.WASMLINE_SERVICE, artifact.invocationProtocol)
        assertEquals(WasmlineComponentServiceContract.DEFAULT_EXPORT, artifact.exportName)
        assertEquals(
            WasmlineComponentServiceContract.DEFAULT_CODEC,
            artifact.contractMetadata[WasmlineComponentServiceContract.METADATA_CODEC],
        )
    }

    @Test
    fun componentServiceRejectsCoreTransportAbiBeforeEmbedding() = withPipelineDirectory { root ->
        val core = File(root, "plugin.wasm").apply { writeBytes(byteArrayOf(0, 97, 115, 109)) }
        val wit = File(root, "wit").apply { mkdirs() }
        File(wit, "wasmline.wit").writeText("package wasmline:service@1.0.0; world plugin {}")
        val adapter = File(root, "adapter.wasm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val tools = RecordingWasmTools(
            coreWat = """
                (module
                  (import "host" "invoke" (func))
                  (import "env" "bridge_outbound_call_host" (func))
                  (memory (export "memory") 1)
                  (func (export "plugin#invoke"))
                  (func (export "cabi_realloc")))
            """.trimIndent(),
        )

        val failure = kotlin.test.assertFailsWith<IllegalArgumentException> {
            ComponentPipeline(tools).componentize(
                ComponentizeRequest(
                    coreWasm = core,
                    witPath = wit,
                    wasiPreview1Adapter = adapter,
                    outputDirectory = File(root, "output"),
                    productName = "plugin",
                    invocationProtocol = WasmlineInvocationProtocol.WASMLINE_SERVICE,
                    exportName = WasmlineComponentServiceContract.DEFAULT_EXPORT,
                    codec = WasmlineComponentServiceContract.DEFAULT_CODEC,
                    serviceProtocolVersion = WasmlineComponentServiceContract.VERSION,
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("env.bridge_outbound_call_host"))
        assertEquals(listOf("version", "print"), tools.operations)
    }
}

private class RecordingWasmTools(
    private val coreWat: String = """
        (module
          (import "host" "invoke" (func))
          (memory (export "memory") 1)
          (func (export "plugin#invoke"))
          (func (export "cabi_realloc")))
    """.trimIndent(),
) : WasmTools {
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

    override fun createComponent(embeddedWasm: File, adapter: File, outputComponent: File, adapterModule: String): ToolExecutionResult {
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

    override fun printCoreModule(module: File): String {
        operations += "print"
        return coreWat
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
