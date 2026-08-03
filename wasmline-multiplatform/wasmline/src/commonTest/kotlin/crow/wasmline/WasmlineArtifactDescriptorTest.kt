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
