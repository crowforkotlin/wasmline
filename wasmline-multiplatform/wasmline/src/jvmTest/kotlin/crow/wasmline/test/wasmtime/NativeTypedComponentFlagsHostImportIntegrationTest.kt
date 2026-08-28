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
import crow.wasmline.WasmlineRuntime
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invokeComponentResult
import crow.wasmline.platformWasmlineLoadArtifact
import crow.wasmline.platformWasmlineRuntimeCapabilities
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Verifies flags Component host imports through AOT JNI artifacts.
 *
 * Validates flags arguments and results with external `.cwasm`/`.pwasm` only.
 *
 * Date: 2026-08-07
 * Author: crowforkotlin
 */
class NativeTypedComponentFlagsHostImportIntegrationTest {

    @Test
    fun flagsHostImportRoundTripsThroughTheJniRegistry() {
        if (System.getenv(LIVE_TESTS_ENV) != "1") return

        val artifact = copyFixture()
        try {
            val handle = loadComponent(artifact)
            try {
                handle.bindComponentHost(flagsHostRegistry())
                assertEquals(
                    listOf(WasmlineComponentValue.FlagsValue(listOf("safe"))),
                    invoke(
                        handle,
                        WasmlineComponentValue.FlagsValue(listOf("fast", "trace")),
                    ).values,
                )
                assertEquals(
                    listOf(WasmlineComponentValue.FlagsValue(listOf("fast", "safe", "trace"))),
                    invoke(handle, WasmlineComponentValue.FlagsValue(emptyList())).values,
                )
            } finally {
                handle.close()
            }
        } finally {
            WasmlineRuntime.shutdown()
            artifact.delete()
        }
    }

    private fun invoke(handle: crow.wasmline.Wasmline, mode: WasmlineComponentValue.FlagsValue): WasmlineComponentCallResult {
        val invocation = handle.invokeComponentResult(
            exportName = "run",
            arguments = listOf(mode),
        )
        return assertIs<WasmlineCallResult.Success<WasmlineComponentCallResult>>(invocation).value
    }

    private fun flagsHostRegistry(): WasmlineComponentHostRegistry {
        val interfaceId = WasmlineComponentInterfaceId.of("example:host/flags")
        val functionId = WasmlineComponentFunctionId.of(interfaceId, "inspect")
        return WasmlineComponentHostRegistry.builder()
            .register(
                functionId,
                WasmlineComponentHostAdapter { arguments ->
                    val mode = assertIs<WasmlineComponentValue.FlagsValue>(arguments.single())
                    when (mode.names.toSet()) {
                        setOf("fast", "trace") -> WasmlineCallResult.Success(
                            listOf(WasmlineComponentValue.FlagsValue(listOf("safe"))),
                        )

                        emptySet<String>() -> WasmlineCallResult.Success(
                            listOf(
                                WasmlineComponentValue.FlagsValue(
                                    listOf("fast", "safe", "trace"),
                                ),
                            ),
                        )

                        else -> error("Unexpected flags: ${mode.names}")
                    }
                },
            )
            .build()
    }

    private fun loadComponent(artifact: File): crow.wasmline.Wasmline {
        val runtime = platformWasmlineRuntimeCapabilities()
        val format = componentAotFormat(artifact.name)
        val state = platformWasmlineLoadArtifact(
            descriptor = nativeTestArtifactDescriptor(
                path = artifact.absolutePath,
                artifactFormat = format,
                runtime = runtime,
                executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
                invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
                exportName = "run",
            ),
            config = WasmlineConfig(supportConcurrent = false),
        )
        return assertIs<WasmlineLoadState.Success>(state).wasmline
    }

    private fun copyFixture(): File {
        val source = requireNotNull(System.getenv(FIXTURE_ENV)) {
            "$FIXTURE_ENV must be set when $LIVE_TESTS_ENV=1."
        }.let(::File)
        require(source.isFile) { "$FIXTURE_ENV does not point to a file: ${source.absolutePath}" }
        val format = componentAotFormat(source.name)
        val suffix = when (format) {
            WasmlineArtifactFormat.CWASM -> ".cwasm"
            WasmlineArtifactFormat.PWASM -> ".pwasm"
            WasmlineArtifactFormat.RAW_WASM -> error("Flags Component fixture cannot use raw Wasm.")
        }
        return File.createTempFile("wasmline-component-flags-host-", suffix).apply {
            source.copyTo(this, overwrite = true)
            deleteOnExit()
        }
    }

    private fun componentAotFormat(filename: String): WasmlineArtifactFormat = when {
        filename.endsWith(".cwasm", ignoreCase = true) -> WasmlineArtifactFormat.CWASM
        filename.endsWith(".pwasm", ignoreCase = true) -> WasmlineArtifactFormat.PWASM
        else -> error("Flags Component fixture must be a precompiled .cwasm or .pwasm artifact.")
    }

    private companion object {
        const val LIVE_TESTS_ENV = "WASMLINE_LIVE_TESTS"
        const val FIXTURE_ENV = "WASMLINE_TEST_COMPONENT_FLAGS_HOST"
    }
}
