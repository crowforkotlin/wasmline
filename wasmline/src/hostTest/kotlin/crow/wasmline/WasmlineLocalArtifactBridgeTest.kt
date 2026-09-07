package crow.wasmline

import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val INCOMPATIBLE_PROFILE_ID =
    "sha256:0000000000000000000000000000000000000000000000000000000000000000"
private const val CRANELIFT_PROFILE_ID =
    "sha256:1111111111111111111111111111111111111111111111111111111111111111"
private const val PULLEY_PROFILE_ID =
    "sha256:2222222222222222222222222222222222222222222222222222222222222222"

/**
 * Verifies local artifact validation before a platform backend is invoked.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
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
            backend = WasmlineEngineKind.CRANELIFT,
            supportedArtifactFormats = setOf(WasmlineArtifactFormat.CWASM, WasmlineArtifactFormat.PWASM),
            wasmtimeVersion = "12.3.4",
            aotCompatibilityProfileIdsByBackend = mapOf(
                WasmlineEngineKind.CRANELIFT to setOf(CRANELIFT_PROFILE_ID),
                WasmlineEngineKind.PULLEY to setOf(PULLEY_PROFILE_ID),
            ),
            nativeBridgeAbiVersion = WasmlineReleaseIdentity.NATIVE_BRIDGE_ABI_VERSION,
            wasmlineReleaseVersion = WasmlineReleaseIdentity.RELEASE_VERSION,
            operatingSystem = "linux",
            architecture = "x86_64",
            pointerWidth = 64,
            supportedCpuFeatureProfiles = setOf("baseline-v1"),
        )
        val platform = FakePlatform(
            descriptorValidation = { descriptor -> descriptor.runtimeCompatibilityError(runtime) },
        )

        val result = WasmlineLocalArtifactBridge.load(
            descriptor = WasmlineArtifactDescriptor(
                path = "plugin.cwasm",
                artifactFormat = WasmlineArtifactFormat.CWASM,
                operatingSystem = "linux",
                architecture = "x86_64",
                pointerWidth = 64,
                cpuFeatureProfile = "baseline-v1",
                aotCompatibilityProfileId = INCOMPATIBLE_PROFILE_ID,
            ),
            config = WasmlineConfig(),
            platform = platform,
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertTrue(failure.failure.message.contains("is not supported by the linked CRANELIFT runtime"))
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

    /** Preserves a structured native load failure. */
    @Test
    fun reportsNativeLoadFailure() {
        val platform = FakePlatform(
            resolvedArtifact = ResolvedPrecompiledArtifact("plugin.wasm", "module"),
            loadResult = WasmlineCallResult.Failure(
                WasmlineFailure(
                    code = WasmlineErrorCode.MODULE_FORMAT_INVALID,
                    message = "Native artifact could not be deserialized.",
                    details = "Invalid ELF header".encodeToByteArray(),
                ),
            ),
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
        assertEquals(WasmlineErrorCode.MODULE_FORMAT_INVALID, failure.failure.code)
        assertEquals("Native artifact could not be deserialized.", failure.failure.message)
        assertEquals("Invalid ELF header", failure.failure.details?.decodeToString())
        assertEquals(1, platform.loadCalls)
    }

    /** Writes structured native diagnostics only when the host configures a logger. */
    @Test
    fun logsNativeLoadFailureWhenLoggerIsConfigured() {
        val previousLogger = WasmlineLog.logger
        val logger = CapturingLogger()
        WasmlineLog.logger = logger
        try {
            val platform = FakePlatform(
                resolvedArtifact = ResolvedPrecompiledArtifact("plugin.wasm", "module"),
                loadResult = WasmlineCallResult.Failure(
                    WasmlineFailure(
                        code = WasmlineErrorCode.MODULE_FORMAT_INVALID,
                        message = "Native artifact could not be deserialized.",
                        details = "Invalid ELF header".encodeToByteArray(),
                    ),
                ),
            )

            val result = WasmlineLocalArtifactBridge.load(
                descriptor = WasmlineArtifactDescriptor(
                    path = "plugin.wasm",
                    artifactFormat = WasmlineArtifactFormat.RAW_WASM,
                ),
                config = WasmlineConfig(),
                platform = platform,
            )

            assertIs<WasmlineLoadState.Failure>(result)
            assertEquals(
                "[WasmlineLocalArtifactBridge] Native artifact could not be deserialized.\nInvalid ELF header",
                logger.errors.single(),
            )
        } finally {
            WasmlineLog.logger = previousLogger
        }
    }

    /**
     * Records platform bridge calls without loading a native artifact.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    private class FakePlatform(
        private val resolvedArtifact: ResolvedPrecompiledArtifact? = ResolvedPrecompiledArtifact("plugin.pwasm", "module"),
        private val loadResult: WasmlineCallResult<Unit> = WasmlineCallResult.Success(Unit),
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

        override fun loadPrecompiled(moduleKey: String, path: String, descriptor: WasmlineArtifactDescriptor): WasmlineCallResult<Unit> {
            loadCalls++
            return loadResult
        }
    }

    /**
     * Captures messages emitted through the configured Wasmline logger.
     *
     * Date: 2026-09-02
     * Author: crowforkotlin
     */
    private class CapturingLogger : WasmlineLogger {
        val errors = mutableListOf<String>()

        override fun info(message: String) = Unit

        override fun debug(message: String) = Unit

        override fun warn(message: String) = Unit

        override fun error(message: String) {
            errors += message
        }
    }
}
