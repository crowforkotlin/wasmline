package crow.wasmline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Tests artifact execution model validation.
 *
 * Verifies execution-model and invocation-protocol validation rules.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
class WasmlineArtifactDescriptorTest {
    /** Rejects an artifact descriptor without a path. */
    @Test
    fun rejectsBlankArtifactPath() {
        val descriptor = WasmlineArtifactDescriptor(path = " ")

        assertEquals("Artifact path must not be blank.", descriptor.validationError())
    }

    /** Allows a raw module to expose an arbitrary set of exports. */
    @Test
    fun rawInvocationMayOmitAnExportName() {
        val descriptor = WasmlineArtifactDescriptor(
            path = "plugin.wasm",
            invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
        )

        assertNull(descriptor.validationError())
    }

    @Test
    fun defaultsDescribeTheLegacyCoreProtocol() {
        val descriptor = WasmlineArtifactDescriptor(path = "plugin.pwasm")

        assertNull(descriptor.artifactFormat)
        assertEquals(WasmlineExecutionModel.CORE_WASM, descriptor.executionModel)
        assertEquals(WasmlineInvocationProtocol.WASMLINE_SERVICE, descriptor.invocationProtocol)
        assertNull(descriptor.validationError())
    }

    @Test
    fun rawExportRequiresCoreModelButNotExportName() {
        val descriptor = WasmlineArtifactDescriptor(
            path = "plugin.pwasm",
            executionModel = WasmlineExecutionModel.CORE_WASM,
            invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
        )

        assertNull(descriptor.validationError())
    }

    @Test
    fun componentExportDoesNotRequireWitSource() {
        val descriptor = WasmlineArtifactDescriptor(
            path = "plugin.cwasm",
            executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
            invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
        )

        assertNull(descriptor.validationError())
    }

    @Test
    fun componentServiceAcceptsItsFixedOrImplicitExport() {
        val implicit = WasmlineArtifactDescriptor(
            path = "plugin.cwasm",
            executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
            invocationProtocol = WasmlineInvocationProtocol.WASMLINE_SERVICE,
        )
        val explicit = implicit.copy(exportName = WasmlineComponentServiceContract.DEFAULT_EXPORT)

        assertNull(implicit.validationError())
        assertNull(explicit.validationError())
    }

    @Test
    fun componentServiceRejectsAnArbitraryExport() {
        val descriptor = WasmlineArtifactDescriptor(
            path = "plugin.cwasm",
            executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
            invocationProtocol = WasmlineInvocationProtocol.WASMLINE_SERVICE,
            exportName = "calculator/evaluate",
        )

        assertEquals(
            "WASMLINE_SERVICE Component exportName must be '${WasmlineComponentServiceContract.DEFAULT_EXPORT}'.",
            descriptor.validationError(),
        )
    }

    /** Verifies that AOT physical formats do not determine the execution model. */
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
                aotCompatibilityProfileId = "sha256:${"a".repeat(64)}",
                executionModel = executionModel,
                invocationProtocol = if (component) {
                    WasmlineInvocationProtocol.COMPONENT_EXPORT
                } else {
                    WasmlineInvocationProtocol.WASMLINE_SERVICE
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
    fun rejectsComponentServiceWithArbitraryExport() {
        val descriptor = WasmlineArtifactDescriptor(
            path = "plugin.cwasm",
            executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
            invocationProtocol = WasmlineInvocationProtocol.WASMLINE_SERVICE,
            exportName = "add",
        )

        assertEquals(
            "WASMLINE_SERVICE Component exportName must be '${WasmlineComponentServiceContract.DEFAULT_EXPORT}'.",
            descriptor.validationError(),
        )
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

    /** Rejects raw ABI metadata attached to a non-raw invocation protocol. */
    @Test
    fun rawAbiMetadataRequiresRawExportProtocol() {
        val descriptor = WasmlineArtifactDescriptor(
            path = "plugin.wasm",
            invocationProtocol = WasmlineInvocationProtocol.WASMLINE_SERVICE,
            rawAbi = RawAbiMetadata(),
        )

        assertEquals(
            "rawAbi metadata requires the RAW_EXPORT invocation protocol.",
            descriptor.validationError(),
        )
    }

    /** Rejects metadata versions newer than the runtime understands. */
    @Test
    fun rawAbiMetadataRejectsUnknownVersion() {
        val descriptor = WasmlineArtifactDescriptor(
            path = "plugin.wasm",
            invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
            rawAbi = RawAbiMetadata(version = RawAbiMetadata.CURRENT_VERSION + 1),
        )

        assertEquals(
            "Unsupported rawAbi metadata version ${RawAbiMetadata.CURRENT_VERSION + 1}.",
            descriptor.validationError(),
        )
    }

    /** Rejects duplicate export declarations in raw ABI metadata. */
    @Test
    fun rawAbiMetadataRejectsDuplicateExports() {
        assertFailsWith<IllegalArgumentException> {
            RawAbiMetadata(
                exports = listOf(
                    RawExport("add", RawExportKind.FUNCTION),
                    RawExport("add", RawExportKind.FUNCTION),
                ),
            )
        }
    }

    /** Rejects duplicate import declarations in raw ABI metadata. */
    @Test
    fun rawAbiMetadataRejectsDuplicateImports() {
        assertFailsWith<IllegalArgumentException> {
            RawAbiMetadata(
                imports = listOf(
                    RawImportDeclaration("env", "host_add", RawFunctionSignature()),
                    RawImportDeclaration("env", "host_add", RawFunctionSignature()),
                ),
            )
        }
    }
}
