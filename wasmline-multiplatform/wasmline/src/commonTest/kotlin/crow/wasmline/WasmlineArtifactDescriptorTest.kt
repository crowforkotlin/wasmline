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

class WasmlineArtifactDescriptorTest {
    @Test
    fun defaultsDescribeTheLegacyCoreProtocol() {
        val descriptor = WasmlineArtifactDescriptor(path = "plugin.pwasm")

        assertEquals(WasmlineExecutionModel.CORE_WASM, descriptor.executionModel)
        assertEquals(WasmlineInvocationProtocol.WASMLINE_CORE_V1, descriptor.invocationProtocol)
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
    fun rejectsComponentWithCoreProtocol() {
        val descriptor = WasmlineArtifactDescriptor(
            path = "plugin.cwasm",
            executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
            invocationProtocol = WasmlineInvocationProtocol.WASMLINE_CORE_V1,
            exportName = "add",
        )

        assertEquals("COMPONENT_MODEL cannot use WASMLINE_CORE_V1.", descriptor.validationError())
    }
}
