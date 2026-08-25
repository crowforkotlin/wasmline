package crow.wasmline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val INCOMPATIBLE_WASMTIME_VERSION = "0.0.0"

class WasmlineRuntimeCapabilitiesTest {
    private val host = WasmlineRuntimeCapabilities(
        wasmtimeVersion = "12.3.4",
        supportsCranelift = true,
        supportsPulley = true,
        targetOs = "linux",
        targetCpu = "x86_64",
        is64Bit = true,
    )

    @Test
    fun acceptsMatchingCoreAndComponentAotMetadata() {
        listOf(
            cwasmDescriptor(WasmlineExecutionModel.CORE_WASM),
            cwasmDescriptor(WasmlineExecutionModel.COMPONENT_MODEL),
            pwasmDescriptor(WasmlineExecutionModel.CORE_WASM),
            pwasmDescriptor(WasmlineExecutionModel.COMPONENT_MODEL),
        ).forEach { descriptor ->
            assertNull(descriptor.runtimeCompatibilityError(host))
        }
    }

    @Test
    fun derivesImmutableEngineVariantPolicy() {
        assertEquals(WasmlineNativeBackend.CRANELIFT, host.nativeBackendPolicy)
        val craneliftRuntime =
            WasmlineNativeRuntimeInfo(
                backend = WasmlineNativeBackend.CRANELIFT,
                wasmtimeVersion = "12.3.4",
                targetOs = "linux",
                targetCpu = "x86_64",
                is64Bit = true,
            )
        assertEquals(craneliftRuntime, host.nativeRuntimeInfo)
        assertEquals(
            setOf(WasmlineEngineKind.PULLEY, WasmlineEngineKind.CRANELIFT),
            craneliftRuntime.supportedEngines,
        )
        assertEquals(
            WasmlineNativeBackend.PULLEY,
            host.copy(supportsCranelift = false).nativeBackendPolicy,
        )
        assertEquals(
            setOf(WasmlineEngineKind.PULLEY),
            requireNotNull(host.copy(supportsCranelift = false).nativeRuntimeInfo).supportedEngines,
        )
        assertNull(
            host.copy(
                supportsCranelift = false,
                supportsPulley = false,
            ).nativeBackendPolicy,
        )
        assertNull(
            host.copy(
                supportsCranelift = false,
                supportsPulley = false,
            ).nativeRuntimeInfo,
        )
    }

    @Test
    fun rejectsMissingMalformedAndDifferentWasmtimeVersions() {
        assertEquals(
            "AOT artifact targetCompilerVersion must use 'wasmtime-x.y.z'.",
            cwasmDescriptor().copy(targetCompilerVersion = null).runtimeCompatibilityError(host),
        )
        assertEquals(
            "AOT artifact targetCompilerVersion must use 'wasmtime-x.y.z'.",
            cwasmDescriptor().copy(targetCompilerVersion = "12.3.4").runtimeCompatibilityError(host),
        )
        assertEquals(
            "AOT artifact requires Wasmtime $INCOMPATIBLE_WASMTIME_VERSION, " +
                "but the native runtime is 12.3.4.",
            cwasmDescriptor()
                .copy(targetCompilerVersion = "wasmtime-$INCOMPATIBLE_WASMTIME_VERSION")
                .runtimeCompatibilityError(host),
        )
    }

    @Test
    fun rejectsUnsupportedBackendsBeforeNativeLoading() {
        assertEquals(
            "CWASM requires a Cranelift-capable native runtime.",
            cwasmDescriptor().runtimeCompatibilityError(host.copy(supportsCranelift = false)),
        )
        assertEquals(
            "PWASM requires a Pulley-capable native runtime.",
            pwasmDescriptor().runtimeCompatibilityError(host.copy(supportsPulley = false)),
        )
    }

    @Test
    fun rejectsWrongCwasmTargetAndPwasmBitness() {
        assertEquals(
            "CWASM target linux/aarch64/64-bit does not match native runtime linux/x86_64/64-bit.",
            cwasmDescriptor().copy(targetCpu = "aarch64").runtimeCompatibilityError(host),
        )
        assertEquals(
            "PWASM 32-bit target does not match native runtime 64-bit.",
            pwasmDescriptor().copy(targetCpu = "pulley32", is64Bit = false).runtimeCompatibilityError(host),
        )
    }

    @Test
    fun preservesLegacyAndRawDescriptorBehavior() {
        assertNull(WasmlineArtifactDescriptor(path = "legacy.cwasm").runtimeCompatibilityError(host))
        assertNull(
            WasmlineArtifactDescriptor(
                path = "browser.wasm",
                artifactFormat = WasmlineArtifactFormat.RAW_WASM,
            ).runtimeCompatibilityError(host),
        )
    }

    private fun cwasmDescriptor(executionModel: WasmlineExecutionModel = WasmlineExecutionModel.CORE_WASM): WasmlineArtifactDescriptor =
        aotDescriptor(WasmlineArtifactFormat.CWASM, executionModel).copy(
            targetCpu = "x86_64",
            targetOs = "linux",
            is64Bit = true,
        )

    private fun pwasmDescriptor(executionModel: WasmlineExecutionModel = WasmlineExecutionModel.CORE_WASM): WasmlineArtifactDescriptor =
        aotDescriptor(WasmlineArtifactFormat.PWASM, executionModel).copy(
            targetCpu = "pulley64",
            targetOs = null,
            is64Bit = true,
        )

    private fun aotDescriptor(format: WasmlineArtifactFormat, executionModel: WasmlineExecutionModel): WasmlineArtifactDescriptor {
        val component = executionModel == WasmlineExecutionModel.COMPONENT_MODEL
        return WasmlineArtifactDescriptor(
            path = if (format == WasmlineArtifactFormat.CWASM) "plugin.cwasm" else "plugin.pwasm",
            artifactFormat = format,
            targetCompilerVersion = "wasmtime-12.3.4",
            executionModel = executionModel,
            invocationProtocol = if (component) {
                WasmlineInvocationProtocol.COMPONENT_EXPORT
            } else {
                WasmlineInvocationProtocol.WASMLINE_SERVICE
            },
            exportName = if (component) "plugin/invoke" else null,
        )
    }
}
