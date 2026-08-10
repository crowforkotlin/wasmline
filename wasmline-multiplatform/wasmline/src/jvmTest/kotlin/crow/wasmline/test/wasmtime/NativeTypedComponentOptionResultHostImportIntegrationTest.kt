/**
 * Verifies option and result Component host imports through AOT JNI artifacts.
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
import crow.wasmline.invokeComponentResult
import crow.wasmline.wasmlineLoadArtifact
import crow.wasmline.wasmlineRuntimeCapabilities
import crow.wasmline.wasmlineShutdown
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Validates option arguments and result-valued Component imports with external AOT artifacts. */
class NativeTypedComponentOptionResultHostImportIntegrationTest {

    @Test
    fun optionAndResultHostImportRoundTripsThroughTheJniRegistry() {
        if (System.getenv(LIVE_TESTS_ENV) != "1") return

        val artifact = copyFixture()
        try {
            val handle = loadComponent(artifact)
            try {
                handle.bindComponentHost(optionResultHostRegistry())
                assertEquals(
                    listOf(
                        WasmlineComponentValue.ResultValue(
                            isOk = true,
                            value = WasmlineComponentValue.S32(19),
                        ),
                    ),
                    invoke(handle, WasmlineComponentValue.OptionValue(WasmlineComponentValue.S32(9))).values,
                )
                assertEquals(
                    listOf(
                        WasmlineComponentValue.ResultValue(
                            isOk = false,
                            value = WasmlineComponentValue.S32(17),
                        ),
                    ),
                    invoke(handle, WasmlineComponentValue.OptionValue()).values,
                )
            } finally {
                handle.close()
            }
        } finally {
            wasmlineShutdown()
            artifact.delete()
        }
    }

    private fun invoke(handle: crow.wasmline.Wasmline, maybe: WasmlineComponentValue.OptionValue): WasmlineComponentCallResult {
        val invocation = handle.invokeComponentResult(
            exportName = "run",
            arguments = listOf(maybe),
        )
        return assertIs<WasmlineCallResult.Success<WasmlineComponentCallResult>>(invocation).value
    }

    private fun optionResultHostRegistry(): WasmlineComponentHostRegistry {
        val interfaceId = WasmlineComponentInterfaceId.of("example:host/option-result")
        val functionId = WasmlineComponentFunctionId.of(interfaceId, "inspect")
        return WasmlineComponentHostRegistry.builder()
            .register(
                functionId,
                WasmlineComponentHostAdapter { arguments ->
                    val maybe = assertIs<WasmlineComponentValue.OptionValue>(arguments.single())
                    val value = (maybe.value as? WasmlineComponentValue.S32)?.value ?: 7
                    WasmlineCallResult.Success(
                        listOf(
                            WasmlineComponentValue.ResultValue(
                                isOk = maybe.value != null,
                                value = WasmlineComponentValue.S32(value + 10),
                            ),
                        ),
                    )
                },
            )
            .build()
    }

    private fun loadComponent(artifact: File): crow.wasmline.Wasmline {
        val runtime = wasmlineRuntimeCapabilities()
        val format = componentAotFormat(artifact.name)
        val state = wasmlineLoadArtifact(
            descriptor = WasmlineArtifactDescriptor(
                path = artifact.absolutePath,
                artifactFormat = format,
                targetCpu = when (format) {
                    WasmlineArtifactFormat.CWASM -> runtime.targetCpu
                    WasmlineArtifactFormat.PWASM -> "pulley64"
                    WasmlineArtifactFormat.RAW_WASM -> error("Option/result Component fixture cannot use raw Wasm.")
                },
                targetOs = if (format == WasmlineArtifactFormat.CWASM) runtime.targetOs else null,
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
        val source = requireNotNull(System.getenv(FIXTURE_ENV)) {
            "$FIXTURE_ENV must be set when $LIVE_TESTS_ENV=1."
        }.let(::File)
        require(source.isFile) { "$FIXTURE_ENV does not point to a file: ${source.absolutePath}" }
        val format = componentAotFormat(source.name)
        val suffix = when (format) {
            WasmlineArtifactFormat.CWASM -> ".cwasm"
            WasmlineArtifactFormat.PWASM -> ".pwasm"
            WasmlineArtifactFormat.RAW_WASM -> error("Option/result Component fixture cannot use raw Wasm.")
        }
        return File.createTempFile("wasmline-component-option-result-host-", suffix).apply {
            source.copyTo(this, overwrite = true)
            deleteOnExit()
        }
    }

    private fun componentAotFormat(filename: String): WasmlineArtifactFormat = when {
        filename.endsWith(".cwasm", ignoreCase = true) -> WasmlineArtifactFormat.CWASM
        filename.endsWith(".pwasm", ignoreCase = true) -> WasmlineArtifactFormat.PWASM
        else -> error("Option/result Component fixture must be a precompiled .cwasm or .pwasm artifact.")
    }

    private companion object {
        const val LIVE_TESTS_ENV = "WASMLINE_LIVE_TESTS"
        const val FIXTURE_ENV = "WASMLINE_TEST_COMPONENT_OPTION_RESULT_HOST"
    }
}
