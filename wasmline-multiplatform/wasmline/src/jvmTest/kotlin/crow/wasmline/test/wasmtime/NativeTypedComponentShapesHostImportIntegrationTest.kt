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
 * Verifies list, tuple and record Component host imports through AOT JNI artifacts.
 *
 * Validates structured Component imports with generated `.cwasm`/`.pwasm` artifacts.
 *
 * Date: 2026-09-01
 * Author: crowforkotlin
 */
class NativeTypedComponentShapesHostImportIntegrationTest {

    @Test
    fun structuredHostImportRoundTripsThroughTheJniRegistry() {
        val artifact = copyFixture()
        try {
            val handle = loadComponent(artifact)
            try {
                handle.bindComponentHost(shapesHostRegistry())
                val invocation = handle.invokeComponentResult(
                    exportName = "run",
                    arguments = listOf(
                        WasmlineComponentValue.ListValue(
                            listOf(
                                WasmlineComponentValue.U8(1u),
                                WasmlineComponentValue.U8(2u),
                                WasmlineComponentValue.U8(3u),
                            ),
                        ),
                        WasmlineComponentValue.TupleValue(
                            listOf(
                                WasmlineComponentValue.U32(7u),
                                WasmlineComponentValue.S32(-2),
                            ),
                        ),
                        WasmlineComponentValue.RecordValue(
                            listOf(
                                WasmlineComponentValue.RecordField("count", WasmlineComponentValue.S32(9)),
                                WasmlineComponentValue.RecordField("enabled", WasmlineComponentValue.Bool(true)),
                            ),
                        ),
                    ),
                )
                val success = assertIs<WasmlineCallResult.Success<WasmlineComponentCallResult>>(invocation)
                assertEquals(
                    listOf(WasmlineComponentValue.S32(21)),
                    success.value.values,
                )
            } finally {
                handle.close()
            }
        } finally {
            WasmlineRuntime.shutdown()
            artifact.delete()
        }
    }

    private fun shapesHostRegistry(): WasmlineComponentHostRegistry {
        val interfaceId = WasmlineComponentInterfaceId.of("example:host/shapes")
        val functionId = WasmlineComponentFunctionId.of(interfaceId, "inspect")
        return WasmlineComponentHostRegistry.builder()
            .register(
                functionId,
                WasmlineComponentHostAdapter { arguments ->
                    val bytes = assertIs<WasmlineComponentValue.ListValue>(arguments[0])
                    val pair = assertIs<WasmlineComponentValue.TupleValue>(arguments[1])
                    val stats = assertIs<WasmlineComponentValue.RecordValue>(arguments[2])
                    val pairFirst = assertIs<WasmlineComponentValue.U32>(pair.values[0]).value
                    val pairSecond = assertIs<WasmlineComponentValue.S32>(pair.values[1]).value
                    val count = assertIs<WasmlineComponentValue.S32>(stats.fields[0].value).value
                    val enabled = assertIs<WasmlineComponentValue.Bool>(stats.fields[1].value).value
                    val checksum = bytes.values.sumOf { assertIs<WasmlineComponentValue.U8>(it).value.toInt() }
                    WasmlineCallResult.Success(
                        listOf(
                            WasmlineComponentValue.S32(
                                checksum + pairFirst.toInt() + pairSecond + count + if (enabled) 1 else 0,
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

    private fun copyFixture(): File = NativeFixtureTestSupport.copy("component-typed-shapes")

    private fun componentAotFormat(filename: String): WasmlineArtifactFormat = when {
        filename.endsWith(".cwasm", ignoreCase = true) -> WasmlineArtifactFormat.CWASM
        filename.endsWith(".pwasm", ignoreCase = true) -> WasmlineArtifactFormat.PWASM
        else -> error("Structured Component fixture must be a precompiled .cwasm or .pwasm artifact.")
    }
}
