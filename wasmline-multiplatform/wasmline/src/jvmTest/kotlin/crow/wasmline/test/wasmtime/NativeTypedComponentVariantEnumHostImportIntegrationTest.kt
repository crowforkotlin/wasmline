/**
 * Verifies variant and enum Component host imports through AOT JNI artifacts.
 *
 * Date: 2026-08-07
 * Author: crowforkotlin
 */
package crow.wasmline.test.wasmtime

import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineArtifactFormat
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

/** Validates variant and enum Component imports with external `.cwasm`/`.pwasm`. */
class NativeTypedComponentVariantEnumHostImportIntegrationTest {

    @Test
    fun variantAndEnumHostImportRoundTripsThroughTheJniRegistry() {
        if (System.getenv(LIVE_TESTS_ENV) != "1") return

        val artifact = copyFixture()
        try {
            val handle = loadComponent(artifact)
            try {
                handle.bindComponentHost(variantEnumHostRegistry())
                assertEquals(
                    listOf(WasmlineComponentValue.S32(17)),
                    invoke(
                        handle,
                        WasmlineComponentValue.VariantValue(
                            discriminant = "number",
                            value = WasmlineComponentValue.S32(7),
                        ),
                        WasmlineComponentValue.EnumValue("red"),
                    ),
                )
                assertEquals(
                    listOf(WasmlineComponentValue.S32(20)),
                    invoke(
                        handle,
                        WasmlineComponentValue.VariantValue(discriminant = "none"),
                        WasmlineComponentValue.EnumValue("blue"),
                    ),
                )
            } finally {
                handle.close()
            }
        } finally {
            wasmlineShutdown()
            artifact.delete()
        }
    }

    private fun invoke(
        handle: crow.wasmline.Wasmline,
        choice: WasmlineComponentValue.VariantValue,
        shade: WasmlineComponentValue.EnumValue,
    ): List<WasmlineComponentValue> {
        val invocation = handle.invokeComponentResult(
            exportName = "run",
            arguments = listOf(choice, shade),
        )
        return assertIs<WasmlineCallResult.Success<crow.wasmline.WasmlineComponentCallResult>>(invocation).value.values
    }

    private fun variantEnumHostRegistry(): WasmlineComponentHostRegistry {
        val interfaceId = WasmlineComponentInterfaceId.of("example:host/variant-enum")
        val functionId = WasmlineComponentFunctionId.of(interfaceId, "inspect")
        return WasmlineComponentHostRegistry.builder()
            .register(
                functionId,
                WasmlineComponentHostAdapter { arguments ->
                    val choice = assertIs<WasmlineComponentValue.VariantValue>(arguments[0])
                    val shade = assertIs<WasmlineComponentValue.EnumValue>(arguments[1])
                    val choiceScore = when (choice.discriminant) {
                        "number" -> assertIs<WasmlineComponentValue.S32>(choice.value).value

                        "none" -> {
                            check(choice.value == null) { "The none variant must not carry a payload." }
                            0
                        }

                        else -> error("Unexpected variant case: ${choice.discriminant}")
                    }
                    val shadeScore = when (shade.name) {
                        "red" -> 10
                        "blue" -> 20
                        else -> error("Unexpected enum case: ${shade.name}")
                    }
                    WasmlineCallResult.Success(listOf(WasmlineComponentValue.S32(choiceScore + shadeScore)))
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
                    WasmlineArtifactFormat.RAW_WASM -> error("Variant/enum Component fixture cannot use raw Wasm.")
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
            WasmlineArtifactFormat.RAW_WASM -> error("Variant/enum Component fixture cannot use raw Wasm.")
        }
        return File.createTempFile("wasmline-component-variant-enum-host-", suffix).apply {
            source.copyTo(this, overwrite = true)
            deleteOnExit()
        }
    }

    private fun componentAotFormat(filename: String): WasmlineArtifactFormat = when {
        filename.endsWith(".cwasm", ignoreCase = true) -> WasmlineArtifactFormat.CWASM
        filename.endsWith(".pwasm", ignoreCase = true) -> WasmlineArtifactFormat.PWASM
        else -> error("Variant/enum Component fixture must be a precompiled .cwasm or .pwasm artifact.")
    }

    private companion object {
        const val LIVE_TESTS_ENV = "WASMLINE_LIVE_TESTS"
        const val FIXTURE_ENV = "WASMLINE_TEST_COMPONENT_VARIANT_ENUM_HOST"
    }
}
