/**
 * Tests artifact execution model validation.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
package crow.wasmline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Verifies execution-model and invocation-protocol validation rules. */
class WasmlineArtifactDescriptorTest {
    /** Rejects an artifact descriptor without a path. */
    @Test
    fun rejectsBlankArtifactPath() {
        val descriptor = WasmlineArtifactDescriptor(path = " ")

        assertEquals("Artifact path must not be blank.", descriptor.validationError())
    }

    /** Requires an export name for raw direct invocation. */
    @Test
    fun rawInvocationRequiresAnExportName() {
        val descriptor = WasmlineArtifactDescriptor(
            path = "plugin.wasm",
            invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
        )

        assertEquals("An exportName is required for direct export invocation.", descriptor.validationError())
    }

    @Test
    fun defaultsDescribeTheLegacyCoreProtocol() {
        val descriptor = WasmlineArtifactDescriptor(path = "plugin.pwasm")

        assertNull(descriptor.artifactFormat)
        assertEquals(WasmlineExecutionModel.CORE_WASM, descriptor.executionModel)
        assertEquals(WasmlineInvocationProtocol.WASMLINE_CORE, descriptor.invocationProtocol)
        assertNull(descriptor.validationError())
    }

    @Test
    fun rawExportRequiresCoreModelAndExportName() {
        val descriptor = WasmlineArtifactDescriptor(
            path = "plugin.pwasm",
            executionModel = WasmlineExecutionModel.CORE_WASM,
            invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
        )

        assertEquals("An exportName is required for direct export invocation.", descriptor.validationError())
    }

    @Test
    fun componentExportDoesNotRequireWitSource() {
        val descriptor = WasmlineArtifactDescriptor(
            path = "plugin.cwasm",
            executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
            invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
            exportName = "add",
        )

        assertNull(descriptor.validationError())
    }

    @Test
    fun aotFormatIsIndependentFromExecutionModel() {
        listOf(
            WasmlineArtifactFormat.CWASM to WasmlineExecutionModel.CORE_WASM,
            WasmlineArtifactFormat.PWASM to WasmlineExecutionModel.CORE_WASM,
            WasmlineArtifactFormat.CWASM to WasmlineExecutionModel.COMPONENT_MODEL,
            WasmlineArtifactFormat.PWASM to WasmlineExecutionModel.COMPONENT_MODEL,
        ).forEach { (format, executionModel) ->
            val component = executionModel == WasmlineExecutionModel.COMPONENT_MODEL
            val descriptor = WasmlineArtifactDescriptor(
                path = if (format == WasmlineArtifactFormat.CWASM) "plugin.cwasm" else "plugin.pwasm",
                artifactFormat = format,
                executionModel = executionModel,
                invocationProtocol = if (component) {
                    WasmlineInvocationProtocol.COMPONENT_EXPORT
                } else {
                    WasmlineInvocationProtocol.WASMLINE_CORE
                },
                exportName = if (component) "plugin/invoke" else null,
            )

            assertNull(descriptor.validationError())
        }
    }

    @Test
    fun artifactFormatsContainNoComponentSpecificAotType() {
        assertEquals(
            listOf(
                WasmlineArtifactFormat.RAW_WASM,
                WasmlineArtifactFormat.CWASM,
                WasmlineArtifactFormat.PWASM,
            ),
            WasmlineArtifactFormat.entries,
        )
    }

    @Test
    fun rejectsComponentWithCoreProtocol() {
        val descriptor = WasmlineArtifactDescriptor(
            path = "plugin.cwasm",
            executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
            invocationProtocol = WasmlineInvocationProtocol.WASMLINE_CORE,
            exportName = "add",
        )

        assertEquals("COMPONENT_MODEL cannot use WASMLINE_CORE.", descriptor.validationError())
    }

    /** Rejects the component export protocol on a Core Wasm artifact. */
    @Test
    fun rejectsComponentProtocolOnCoreArtifact() {
        val descriptor = WasmlineArtifactDescriptor(
            path = "plugin.wasm",
            executionModel = WasmlineExecutionModel.CORE_WASM,
            invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
            exportName = "add",
        )

        assertEquals("COMPONENT_EXPORT requires COMPONENT_MODEL.", descriptor.validationError())
    }

    /** Accepts a Core Wasm artifact with an explicitly named raw export. */
    @Test
    fun acceptsRawCoreInvocationWithExportName() {
        val descriptor = WasmlineArtifactDescriptor(
            path = "plugin.wasm",
            invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
            exportName = "add",
        )

        assertNull(descriptor.validationError())
    }
}
