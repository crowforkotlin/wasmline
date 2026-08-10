package crow.wasmline.loader

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineArtifactType
import kotlin.test.Test
import kotlin.test.assertEquals

class WasmlineArtifactDescriptorExtensionsTest {
    @Test
    fun manifestTypeAndExecutionModelMapIndependently() {
        val cases = listOf(
            MappingCase(WasmlineArtifactType.WASM, WasmlineExecutionModel.CORE_WASM, WasmlineArtifactFormat.RAW_WASM),
            MappingCase(
                WasmlineArtifactType.COMPONENT_WASM,
                WasmlineExecutionModel.COMPONENT_MODEL,
                WasmlineArtifactFormat.RAW_WASM,
            ),
            MappingCase(WasmlineArtifactType.CWASM, WasmlineExecutionModel.CORE_WASM, WasmlineArtifactFormat.CWASM),
            MappingCase(WasmlineArtifactType.PWASM, WasmlineExecutionModel.CORE_WASM, WasmlineArtifactFormat.PWASM),
            MappingCase(WasmlineArtifactType.CWASM, WasmlineExecutionModel.COMPONENT_MODEL, WasmlineArtifactFormat.CWASM),
            MappingCase(WasmlineArtifactType.PWASM, WasmlineExecutionModel.COMPONENT_MODEL, WasmlineArtifactFormat.PWASM),
        )

        cases.forEachIndexed { index, case ->
            val component = case.executionModel == WasmlineExecutionModel.COMPONENT_MODEL
            val artifact = WasmlineArtifact(
                type = case.type,
                url = "plugin-$index.bin",
                sha256 = "hash-$index",
                executionModel = case.executionModel,
                invocationProtocol = if (component) {
                    WasmlineInvocationProtocol.COMPONENT_EXPORT
                } else {
                    WasmlineInvocationProtocol.WASMLINE_CORE
                },
                exportName = if (component) "plugin/invoke" else null,
            )

            val descriptor = artifact.toDescriptor("/package/${artifact.url}")

            assertEquals(case.format, descriptor.artifactFormat)
            assertEquals(case.executionModel, descriptor.executionModel)
            assertEquals(artifact.invocationProtocol, descriptor.invocationProtocol)
            assertEquals(artifact.exportName, descriptor.exportName)
        }
    }

    private data class MappingCase(
        val type: WasmlineArtifactType,
        val executionModel: WasmlineExecutionModel,
        val format: WasmlineArtifactFormat,
    )
}
