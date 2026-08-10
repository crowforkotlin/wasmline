/**
 * Verifies native linking for synchronous typed Component Model host imports.
 *
 * Date: 2026-08-07
 * Author: crowforkotlin
 */
package crow.wasmline.test.wasmtime

import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineComponentCallResult
import crow.wasmline.WasmlineComponentFunctionId
import crow.wasmline.WasmlineComponentHostAdapter
import crow.wasmline.WasmlineComponentHostRegistry
import crow.wasmline.WasmlineComponentInterfaceId
import crow.wasmline.WasmlineComponentValue
import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.WasmlineLoadState
import crow.wasmline.bindComponentHost
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invokeComponentResult
import crow.wasmline.wasmlineLoadArtifact
import crow.wasmline.wasmlineRuntimeCapabilities
import crow.wasmline.wasmlineShutdown
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Validates typed Component JNI registry dispatch with external AOT artifacts only. */
class NativeTypedComponentHostImportIntegrationTest {

    @Test
    fun typedHostImportFixtureRequiresAPrecompiledAotSuffix() {
        assertEquals(WasmlineArtifactFormat.CWASM, componentAotFormat("fixture.cwasm"))
        assertEquals(WasmlineArtifactFormat.PWASM, componentAotFormat("fixture.pwasm"))
        assertFailsWith<IllegalArgumentException> {
            componentAotFormat("fixture.wasm")
        }
    }

    @Test
    fun genericTypedImportReachesTheCanonicalMissingAdapterCallbackError() {
        if (!liveTestsEnabled()) return

        val artifact = copyFixture()
        try {
            val handle = loadComponent(artifact)
            try {
                handle.bindComponentHost(WasmlineComponentHostRegistry.builder().build())
                val failure = assertIs<WasmlineCallResult.Failure>(
                    handle.invokeComponentResult(
                        exportName = "run",
                        arguments = listOf(WasmlineComponentValue.S32(41)),
                    ),
                )

                assertTrue(
                    failure.error.code.isComponentCallFailure(),
                    "Expected the native Component callback failure, got ${failure.error.code}.",
                )
                assertContains(
                    failure.error.message,
                    "No typed Component host adapter is registered for 'example:host/api/increment'.",
                )
            } finally {
                handle.close()
            }
        } finally {
            wasmlineShutdown()
            artifact.delete()
        }
    }

    @Test
    fun typedHostImportRoundTripsThroughTheJniRegistry() {
        if (!liveTestsEnabled()) return

        val artifact = copyFixture()
        try {
            val handle = loadComponent(artifact)
            try {
                handle.bindComponentHost(typedHostRegistry())
                val success = assertIs<WasmlineCallResult.Success<WasmlineComponentCallResult>>(
                    handle.invokeComponentResult(
                        exportName = "run",
                        arguments = listOf(WasmlineComponentValue.S32(41)),
                    ),
                )
                assertEquals(listOf(WasmlineComponentValue.S32(42)), success.value.values)
            } finally {
                handle.close()
            }
        } finally {
            wasmlineShutdown()
            artifact.delete()
        }
    }

    private fun typedHostRegistry(): WasmlineComponentHostRegistry {
        val interfaceId = WasmlineComponentInterfaceId.of("example:host/api")
        val functionId = WasmlineComponentFunctionId.of(interfaceId, "increment")
        return WasmlineComponentHostRegistry.builder()
            .register(
                functionId,
                WasmlineComponentHostAdapter { arguments ->
                    val value = assertIs<WasmlineComponentValue.S32>(arguments.single()).value
                    WasmlineCallResult.Success(listOf(WasmlineComponentValue.S32(value + 1)))
                },
            )
            .build()
    }

    private fun loadComponent(artifact: File): crow.wasmline.Wasmline {
        val artifactFormat = componentAotFormat(artifact.name)
        val runtime = wasmlineRuntimeCapabilities()
        val state = wasmlineLoadArtifact(
            descriptor = WasmlineArtifactDescriptor(
                path = artifact.absolutePath,
                artifactFormat = artifactFormat,
                targetCpu = targetCpuFor(artifactFormat, runtime.is64Bit, runtime.targetCpu),
                targetOs = targetOsFor(artifactFormat, runtime.targetOs),
                targetCompilerVersion = "wasmtime-${runtime.wasmtimeVersion}",
                is64Bit = runtime.is64Bit,
                executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
                invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
                exportName = "run",
            ),
            config = WasmlineConfig(supportConcurrent = false),
        )
        return assertIs<WasmlineLoadState.Success>(state).wasmline
    }

    private fun copyFixture(): File {
        val source = requireNotNull(System.getenv(TYPED_HOST_FIXTURE_ENV)) {
            "$TYPED_HOST_FIXTURE_ENV must be set when $LIVE_TESTS_ENV=1."
        }.let(::File)
        require(source.isFile) { "$TYPED_HOST_FIXTURE_ENV does not point to a file: ${source.absolutePath}" }
        val suffix = componentAotFormat(source.name).fileSuffix()
        return File.createTempFile("wasmline-component-typed-host-", suffix).apply {
            source.copyTo(this, overwrite = true)
            deleteOnExit()
        }
    }

    private fun componentAotFormat(filename: String): WasmlineArtifactFormat = when {
        filename.endsWith(".cwasm", ignoreCase = true) -> WasmlineArtifactFormat.CWASM

        filename.endsWith(".pwasm", ignoreCase = true) -> WasmlineArtifactFormat.PWASM

        else -> throw IllegalArgumentException(
            "Typed Component host fixture must be a precompiled .cwasm or .pwasm artifact, not '$filename'.",
        )
    }

    private fun WasmlineArtifactFormat.fileSuffix(): String = when (this) {
        WasmlineArtifactFormat.CWASM -> ".cwasm"
        WasmlineArtifactFormat.PWASM -> ".pwasm"
        WasmlineArtifactFormat.RAW_WASM -> error("Typed Component host fixtures cannot use raw Wasm.")
    }

    private fun targetCpuFor(artifactFormat: WasmlineArtifactFormat, is64Bit: Boolean, runtimeCpu: String): String = when (artifactFormat) {
        WasmlineArtifactFormat.CWASM -> runtimeCpu
        WasmlineArtifactFormat.PWASM -> if (is64Bit) "pulley64" else "pulley32"
        WasmlineArtifactFormat.RAW_WASM -> error("Typed Component host fixtures cannot use raw Wasm.")
    }

    private fun targetOsFor(artifactFormat: WasmlineArtifactFormat, runtimeOs: String): String? = when (artifactFormat) {
        WasmlineArtifactFormat.CWASM -> runtimeOs
        WasmlineArtifactFormat.PWASM -> null
        WasmlineArtifactFormat.RAW_WASM -> error("Typed Component host fixtures cannot use raw Wasm.")
    }

    private fun liveTestsEnabled(): Boolean = System.getenv(LIVE_TESTS_ENV) == "1"

    private fun WasmlineErrorCode.isComponentCallFailure(): Boolean =
        this == WasmlineErrorCode.COMPONENT_CALL_FAILED || this == WasmlineErrorCode.COMPONENT_TRAP

    private companion object {
        const val LIVE_TESTS_ENV = "WASMLINE_LIVE_TESTS"
        const val TYPED_HOST_FIXTURE_ENV = "WASMLINE_TEST_COMPONENT_TYPED_HOST"
    }
}
