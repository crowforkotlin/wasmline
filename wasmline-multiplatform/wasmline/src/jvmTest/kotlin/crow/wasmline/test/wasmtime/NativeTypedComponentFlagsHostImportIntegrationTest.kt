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
 * Validates flags arguments and results with generated `.cwasm`/`.pwasm` artifacts.
 *
 * Date: 2026-09-01
 * Author: crowforkotlin
 */
class NativeTypedComponentFlagsHostImportIntegrationTest {

    @Test
    fun flagsHostImportRoundTripsThroughTheJniRegistry() {
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

    private fun copyFixture(): File = NativeFixtureTestSupport.copy("component-typed-flags")

    private fun componentAotFormat(filename: String): WasmlineArtifactFormat = when {
        filename.endsWith(".cwasm", ignoreCase = true) -> WasmlineArtifactFormat.CWASM
        filename.endsWith(".pwasm", ignoreCase = true) -> WasmlineArtifactFormat.PWASM
        else -> error("Flags Component fixture must be a precompiled .cwasm or .pwasm artifact.")
    }
}
