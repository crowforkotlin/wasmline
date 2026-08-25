package crow.wasmline.loader

import crow.wasmline.RawAbiMetadata
import crow.wasmline.RawExport
import crow.wasmline.RawExportKind
import crow.wasmline.RawFunctionSignature
import crow.wasmline.RawImportDeclaration
import crow.wasmline.RawValueType
import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineArtifactType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalSerializationApi::class)
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
                    WasmlineInvocationProtocol.WASMLINE_SERVICE
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
    fun coreServiceDoesNotReceiveAComponentExport() {
        val descriptor = WasmlineArtifact(
            type = WasmlineArtifactType.CWASM,
            url = "plugin.cwasm",
            sha256 = "hash",
            executionModel = WasmlineExecutionModel.CORE_WASM,
            invocationProtocol = WasmlineInvocationProtocol.WASMLINE_SERVICE,
        ).toDescriptor("/package/plugin.cwasm")

        assertEquals(null, descriptor.exportName)
        assertEquals(emptyMap(), descriptor.contractMetadata)
    }

    @Test
    fun rawAbiMetadataMapsFromManifestToDescriptor() {
        val rawAbi = RawAbiMetadata(
            exports = listOf(
                RawExport(
                    name = "add",
                    kind = RawExportKind.FUNCTION,
                    signature = RawFunctionSignature(
                        parameters = listOf(RawValueType.I32, RawValueType.I32),
                        results = listOf(RawValueType.I32),
                    ),
                ),
            ),
            imports = listOf(
                RawImportDeclaration(
                    module = "env",
                    name = "host_add",
                    signature = RawFunctionSignature(
                        parameters = listOf(RawValueType.I32, RawValueType.I32),
                        results = listOf(RawValueType.I32),
                    ),
                ),
            ),
        )
        val descriptor = WasmlineArtifact(
            type = WasmlineArtifactType.WASM,
            url = "plugin.wasm",
            sha256 = "hash",
            invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
            rawAbi = rawAbi,
        ).toDescriptor("/package/plugin.wasm")

        assertEquals(rawAbi, descriptor.rawAbi)
        assertEquals(null, descriptor.exportName)
        assertEquals(null, descriptor.validationError())
    }

    @Test
    fun rawAbiMetadataProtobufRoundTrips() {
        val artifact = WasmlineArtifact(
            type = WasmlineArtifactType.WASM,
            url = "plugin.wasm",
            sha256 = "hash",
            invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
            rawAbi = RawAbiMetadata(
                exports = listOf(
                    RawExport(
                        "answer",
                        RawExportKind.FUNCTION,
                        RawFunctionSignature(),
                    ),
                ),
                memoryExport = RawAbiMetadata.DEFAULT_MEMORY_EXPORT,
            ),
        )

        val encoded = ProtoBuf.encodeToByteArray(WasmlineArtifact.serializer(), artifact)
        val decoded = ProtoBuf.decodeFromByteArray(WasmlineArtifact.serializer(), encoded)

        assertEquals(artifact, decoded)
    }

    @Test
    fun rawManifestArtifactMayOmitExportName() {
        val descriptor = WasmlineArtifact(
            type = WasmlineArtifactType.WASM,
            url = "plugin.wasm",
            sha256 = "hash",
            executionModel = WasmlineExecutionModel.CORE_WASM,
            invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
        ).toDescriptor("/package/plugin.wasm")

        assertEquals(null, descriptor.exportName)
        assertEquals(null, descriptor.validationError())
    }

    private data class MappingCase(
        val type: WasmlineArtifactType,
        val executionModel: WasmlineExecutionModel,
        val format: WasmlineArtifactFormat,
    )
}
