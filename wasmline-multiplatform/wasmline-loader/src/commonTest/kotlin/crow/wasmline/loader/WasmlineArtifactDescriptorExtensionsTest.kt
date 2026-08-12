package crow.wasmline.loader

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineComponentRpcContract
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

    @Test
    fun legacyComponentRpcMetadataIsNormalizedToTheExplicitProtocol() {
        val artifact = legacyComponentRpcArtifact()

        val descriptor = artifact.toDescriptor("/package/plugin.cwasm")

        assertEquals(WasmlineInvocationProtocol.WASMLINE_COMPONENT_RPC, descriptor.invocationProtocol)
        assertEquals(WasmlineComponentRpcContract.DEFAULT_EXPORT, descriptor.exportName)
        assertEquals(artifact.contractMetadata, descriptor.contractMetadata)
    }

    @Test
    fun incompleteRpcMetadataRemainsANativeTypedComponent() {
        val complete = legacyComponentRpcArtifact()
        WasmlineComponentRpcContract.let { contract ->
            listOf(
                contract.METADATA_WIT_PACKAGE,
                contract.METADATA_PROFILE,
                contract.METADATA_CODEC,
                contract.METADATA_VERSION,
            ).forEach { missingKey ->
                val descriptor = complete.copy(
                    contractMetadata = complete.contractMetadata - missingKey,
                ).toDescriptor("/package/plugin.cwasm")

                assertEquals(
                    WasmlineInvocationProtocol.COMPONENT_EXPORT,
                    descriptor.invocationProtocol,
                    "Missing $missingKey must not trigger legacy RPC normalization.",
                )
            }
        }
    }

    private fun legacyComponentRpcArtifact(): WasmlineArtifact = WasmlineArtifact(
        type = WasmlineArtifactType.CWASM,
        url = "plugin.cwasm",
        sha256 = "hash",
        executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
        invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
        exportName = WasmlineComponentRpcContract.DEFAULT_EXPORT,
        contractMetadata = mapOf(
            WasmlineComponentRpcContract.METADATA_WIT_PACKAGE to WasmlineComponentRpcContract.WIT_PACKAGE,
            WasmlineComponentRpcContract.METADATA_PROFILE to WasmlineComponentRpcContract.PROFILE,
            WasmlineComponentRpcContract.METADATA_CODEC to WasmlineComponentRpcContract.DEFAULT_CODEC,
            WasmlineComponentRpcContract.METADATA_VERSION to WasmlineComponentRpcContract.VERSION,
        ),
    )

    private data class MappingCase(
        val type: WasmlineArtifactType,
        val executionModel: WasmlineExecutionModel,
        val format: WasmlineArtifactFormat,
    )
}
