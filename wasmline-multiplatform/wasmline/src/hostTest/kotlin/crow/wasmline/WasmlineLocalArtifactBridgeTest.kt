package crow.wasmline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val INCOMPATIBLE_WASMTIME_VERSION = "0.0.0"

/** Verifies local artifact validation before a platform backend is invoked. */
class WasmlineLocalArtifactBridgeTest {

    /** Invalid descriptors fail before artifact resolution is attempted. */
    @Test
    fun rejectsInvalidDescriptorBeforeResolution() {
        val platform = FakePlatform()

        val result = WasmlineLocalArtifactBridge.load(
            descriptor = WasmlineArtifactDescriptor(path = ""),
            config = WasmlineConfig(),
            platform = platform,
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertTrue(failure.failure.message.contains("Artifact path must not be blank."))
        assertEquals(0, platform.resolveCalls)
    }

    @Test
    fun rejectsIncompatibleAotMetadataBeforeResolution() {
        val runtime = WasmlineRuntimeCapabilities(
            wasmtimeVersion = "47.0.2",
            supportsCranelift = true,
            supportsPulley = true,
            targetOs = "linux",
            targetCpu = "x86_64",
            is64Bit = true,
        )
        val platform = FakePlatform(
            descriptorValidation = { descriptor -> descriptor.runtimeCompatibilityError(runtime) },
        )

        val result = WasmlineLocalArtifactBridge.load(
            descriptor = WasmlineArtifactDescriptor(
                path = "plugin.cwasm",
                artifactFormat = WasmlineArtifactFormat.CWASM,
                targetCpu = "x86_64",
                targetOs = "linux",
                targetCompilerVersion = "wasmtime-$INCOMPATIBLE_WASMTIME_VERSION",
                is64Bit = true,
            ),
            config = WasmlineConfig(),
            platform = platform,
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertTrue(failure.failure.message.contains("requires Wasmtime $INCOMPATIBLE_WASMTIME_VERSION"))
        assertEquals(0, platform.resolveCalls)
        assertEquals(0, platform.loadCalls)
    }

    @Test
    fun requiresAnExplicitFormatBeforeNativeResolution() {
        val platform = FakePlatform(requiresExplicitFormat = true)

        val result = WasmlineLocalArtifactBridge.load(
            descriptor = WasmlineArtifactDescriptor(path = "plugin.cwasm"),
            config = WasmlineConfig(),
            platform = platform,
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertTrue(failure.failure.message.contains("requires an explicit artifactFormat"))
        assertEquals(0, platform.resolveCalls)
        assertEquals(0, platform.loadCalls)
    }

    @Test
    fun usesTheExplicitFormatInsteadOfTheFileExtensionForLoadState() {
        val cwasm = WasmlineArtifactDescriptor(
            path = "misleading.pwasm",
            artifactFormat = WasmlineArtifactFormat.CWASM,
        )
        val pwasm = WasmlineArtifactDescriptor(
            path = "misleading.wasm",
            artifactFormat = WasmlineArtifactFormat.PWASM,
        )
        val rawExport = WasmlineArtifactDescriptor(
            path = "misleading.cwasm",
            artifactFormat = WasmlineArtifactFormat.PWASM,
            invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
            exportName = "run",
        )

        assertEquals(WasmlineLoadState.CODE_SUCCESS_AOT, cwasm.backendCodeOrNull())
        assertEquals(WasmlineLoadState.CODE_SUCCESS_PULLEY, pwasm.backendCodeOrNull())
        assertEquals(WasmlineLoadState.CODE_SUCCESS_RAW_EXPORT, rawExport.backendCodeOrNull())
    }

    /** Missing local files return a failure without calling the native backend. */
    @Test
    fun reportsMissingArtifact() {
        val platform = FakePlatform(resolvedArtifact = null)

        val result = WasmlineLocalArtifactBridge.load(
            artifactPath = "missing.pwasm",
            config = WasmlineConfig(),
            platform = platform,
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertTrue(failure.failure.message.contains("artifact file not found"))
        assertEquals(1, platform.resolveCalls)
        assertEquals(0, platform.loadCalls)
    }

    /** Unknown artifact extensions are rejected before native loading. */
    @Test
    fun rejectsUnsupportedArtifactExtension() {
        val platform = FakePlatform(
            resolvedArtifact = ResolvedPrecompiledArtifact("plugin.bin", "module"),
        )

        val result = WasmlineLocalArtifactBridge.load(
            artifactPath = "plugin.bin",
            config = WasmlineConfig(),
            platform = platform,
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertTrue(failure.failure.message.contains("Load failure"))
        assertEquals(0, platform.loadCalls)
    }

    /** Converts a false native load result into a structured failure. */
    @Test
    fun reportsNativeLoadFailure() {
        val platform = FakePlatform(
            resolvedArtifact = ResolvedPrecompiledArtifact("plugin.wasm", "module"),
            loadResult = false,
        )

        val result = WasmlineLocalArtifactBridge.load(
            descriptor = WasmlineArtifactDescriptor(
                path = "plugin.wasm",
                artifactFormat = WasmlineArtifactFormat.RAW_WASM,
            ),
            config = WasmlineConfig(),
            platform = platform,
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertEquals(WasmlineLoadState.CODE_FAILURE, failure.code)
        assertEquals(1, platform.loadCalls)
    }

    private class FakePlatform(
        private val resolvedArtifact: ResolvedPrecompiledArtifact? = ResolvedPrecompiledArtifact("plugin.pwasm", "module"),
        private val loadResult: Boolean = true,
        private val descriptorValidation: (WasmlineArtifactDescriptor) -> String? = { null },
        private val requiresExplicitFormat: Boolean = false,
    ) : WasmlinePlatformArtifactBridge {
        var resolveCalls: Int = 0
        var loadCalls: Int = 0

        override fun createWasmline(moduleKey: String, config: WasmlineConfig, descriptor: WasmlineArtifactDescriptor): Wasmline =
            error("Success path is not used by this fake platform.")

        override fun resolveArtifact(path: String): ResolvedPrecompiledArtifact? {
            resolveCalls++
            return resolvedArtifact
        }

        override fun validationError(descriptor: WasmlineArtifactDescriptor): String? = descriptorValidation(descriptor)

        override fun requiresExplicitArtifactFormat(): Boolean = requiresExplicitFormat

        override fun loadPrecompiled(moduleKey: String, path: String, descriptor: WasmlineArtifactDescriptor): Boolean {
            loadCalls++
            return loadResult
        }
    }
}
