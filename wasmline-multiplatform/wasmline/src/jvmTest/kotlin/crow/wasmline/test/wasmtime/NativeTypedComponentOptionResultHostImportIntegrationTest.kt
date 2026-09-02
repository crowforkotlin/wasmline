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
 * Verifies option and result Component host imports through AOT JNI artifacts.
 *
 * Validates option arguments and result-valued Component imports with generated AOT artifacts.
 *
 * Date: 2026-09-01
 * Author: crowforkotlin
 */
class NativeTypedComponentOptionResultHostImportIntegrationTest {

    @Test
    fun optionAndResultHostImportRoundTripsThroughTheJniRegistry() {
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
            WasmlineRuntime.shutdown()
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

    private fun copyFixture(): File = NativeFixtureTestSupport.copy("component-typed-option-result")

    private fun componentAotFormat(filename: String): WasmlineArtifactFormat = when {
        filename.endsWith(".cwasm", ignoreCase = true) -> WasmlineArtifactFormat.CWASM
        filename.endsWith(".pwasm", ignoreCase = true) -> WasmlineArtifactFormat.PWASM
        else -> error("Option/result Component fixture must be a precompiled .cwasm or .pwasm artifact.")
    }
}
