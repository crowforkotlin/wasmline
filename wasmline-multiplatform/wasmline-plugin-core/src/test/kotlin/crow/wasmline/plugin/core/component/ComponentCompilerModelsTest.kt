package crow.wasmline.plugin.core.component

import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineArtifactType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComponentCompilerModelsTest {
    @Test
    fun backendsReuseExistingPhysicalArtifactTypes() {
        assertEquals(WasmlineArtifactType.CWASM, ComponentAotBackend.CRANELIFT.artifactType)
        assertEquals("cwasm", ComponentAotBackend.CRANELIFT.fileExtension)
        assertEquals(WasmlineArtifactType.PWASM, ComponentAotBackend.PULLEY.artifactType)
        assertEquals("pwasm", ComponentAotBackend.PULLEY.fileExtension)
    }

    @Test
    fun defaultEngineOptionsMatchTheVerifiedNativeProfile() {
        val options = ComponentAotEngineOptions()

        assertTrue(options.componentModel)
        assertEquals("drc", options.collector)
        assertTrue(options.gcSupport)
        assertTrue(options.referenceTypes)
        assertFalse(options.threads)
        assertFalse(options.simd)
        assertTrue(options.concurrencySupport)
        assertEquals(512 * 1024, options.maxWasmStack)
        assertEquals(0, options.memoryGuardSize)
        assertEquals(0, options.optimizationLevel)
        assertFalse(options.signalsBasedTraps)
    }

    @Test
    fun incompatibleEngineOptionsAreRejectedByTheModel() {
        assertFailsWith<IllegalArgumentException> { ComponentAotEngineOptions(collector = "null") }
        assertFailsWith<IllegalArgumentException> { ComponentAotEngineOptions(threads = true) }
        assertFailsWith<IllegalArgumentException> { ComponentAotEngineOptions(optimizationLevel = 2) }
    }

    @Test
    fun requestSeparatesFullCompilerInputTargetsAndComponentMetadata() {
        val metadata = ComponentAotArtifactMetadata(contractMetadata = mapOf("wasmline.rpc.codec" to "protobuf"))
        val target = ComponentAotTarget(
            target = "x86_64-linux",
            backend = ComponentAotBackend.CRANELIFT,
            outputFile = File("out/plugin-x86_64-linux.cwasm"),
        )

        val request = ComponentAotCompileRequest(
            wasmtimeCompiler = File("tools/wasmtime"),
            inputComponent = File("build/plugin-component.wasm"),
            targets = listOf(target),
            wasmtimeVersion = "47.0.2",
            artifactMetadata = metadata,
        )

        assertEquals("wasmtime", request.wasmtimeCompiler.name)
        assertEquals(WasmlineExecutionModel.COMPONENT_MODEL, request.artifactMetadata.executionModel)
        assertEquals(WasmlineInvocationProtocol.COMPONENT_EXPORT, request.artifactMetadata.invocationProtocol)
        assertEquals(listOf(target), request.targets)
    }

    @Test
    fun resultAcceptsOnlyComponentArtifactsInTheBackendPhysicalFormat() {
        val metadata = ComponentAotArtifactMetadata(
            exportName = "plugin/invoke",
            contractMetadata = mapOf("wasmline.rpc.codec" to "protobuf"),
        )
        val artifact = componentArtifact(metadata)
        val output = ComponentAotCompileOutput(
            requestedTarget = "pulley64",
            normalizedTarget = "pulley64",
            backend = ComponentAotBackend.PULLEY,
            outputFile = File("out/plugin-pulley64.pwasm"),
            artifact = artifact,
        )

        val result = ComponentAotCompileResult(
            inputComponent = File("build/plugin-component.wasm"),
            inputComponentSha256 = "a".repeat(64),
            wasmtimeVersion = "47.0.2",
            engineOptions = ComponentAotEngineOptions(),
            artifactMetadata = metadata,
            outputs = listOf(output),
        )

        assertEquals(WasmlineArtifactType.PWASM, result.outputs.single().artifact.type)
        assertEquals(WasmlineExecutionModel.COMPONENT_MODEL, result.outputs.single().artifact.executionModel)
    }

    @Test
    fun resultRejectsCoreMetadataEvenWhenThePhysicalFormatMatches() {
        val metadata = ComponentAotArtifactMetadata()
        val coreArtifact = componentArtifact(metadata).copy(
            executionModel = WasmlineExecutionModel.CORE_WASM,
            invocationProtocol = WasmlineInvocationProtocol.WASMLINE_CORE,
        )

        assertFailsWith<IllegalArgumentException> {
            ComponentAotCompileOutput(
                requestedTarget = "pulley64",
                normalizedTarget = "pulley64",
                backend = ComponentAotBackend.PULLEY,
                outputFile = File("out/plugin-pulley64.pwasm"),
                artifact = coreArtifact,
            )
        }
    }

    private fun componentArtifact(metadata: ComponentAotArtifactMetadata): WasmlineArtifact = WasmlineArtifact(
        type = WasmlineArtifactType.PWASM,
        url = "plugin-pulley64.pwasm",
        sha256 = "b".repeat(64),
        targetCpu = "pulley64",
        targetCompilerVersion = "wasmtime-47.0.2",
        is64Bit = true,
        executionModel = metadata.executionModel,
        invocationProtocol = metadata.invocationProtocol,
        exportName = metadata.exportName,
        contractMetadata = metadata.contractMetadata,
    )
}
