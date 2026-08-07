/**
 * Verifies typed string Component Model host imports through AOT JNI artifacts.
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

/** Validates string-valued Component imports with external `.cwasm`/`.pwasm`. */
class NativeTypedComponentStringHostImportIntegrationTest {

    @Test
    fun stringHostImportRoundTripsThroughTheJniRegistry() {
        if (System.getenv(LIVE_TESTS_ENV) != "1") return

        val artifact = copyFixture()
        try {
            val handle = loadComponent(artifact)
            try {
                handle.bindComponentHost(stringHostRegistry())
                val invocation = handle.invokeComponentResult(
                    exportName = "run",
                    arguments = listOf(WasmlineComponentValue.StringValue("world")),
                )
                val success = assertIs<WasmlineCallResult.Success<WasmlineComponentCallResult>>(
                    invocation,
                )
                assertEquals(
                    listOf(WasmlineComponentValue.StringValue("hello world")),
                    success.value.values,
                )
            } finally {
                handle.close()
            }
        } finally {
            wasmlineShutdown()
            artifact.delete()
        }
    }

    private fun stringHostRegistry(): WasmlineComponentHostRegistry {
        val interfaceId = WasmlineComponentInterfaceId.of("example:host/api")
        val functionId = WasmlineComponentFunctionId.of(interfaceId, "greet")
        return WasmlineComponentHostRegistry.builder()
            .register(
                functionId,
                WasmlineComponentHostAdapter { arguments ->
                    val value = assertIs<WasmlineComponentValue.StringValue>(arguments.single()).value
                    WasmlineCallResult.Success(listOf(WasmlineComponentValue.StringValue("hello $value")))
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
                    WasmlineArtifactFormat.RAW_WASM -> error("String Component fixture cannot use raw Wasm.")
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
            WasmlineArtifactFormat.RAW_WASM -> error("String Component fixtures cannot use raw Wasm.")
        }
        return File.createTempFile("wasmline-component-string-host-", suffix).apply {
            source.copyTo(this, overwrite = true)
            deleteOnExit()
        }
    }

    private fun componentAotFormat(filename: String): WasmlineArtifactFormat = when {
        filename.endsWith(".cwasm", ignoreCase = true) -> WasmlineArtifactFormat.CWASM
        filename.endsWith(".pwasm", ignoreCase = true) -> WasmlineArtifactFormat.PWASM
        else -> error("String Component fixture must be a precompiled .cwasm or .pwasm artifact.")
    }

    private companion object {
        const val LIVE_TESTS_ENV = "WASMLINE_LIVE_TESTS"
        const val FIXTURE_ENV = "WASMLINE_TEST_COMPONENT_TYPED_STRING_HOST"
    }
}
