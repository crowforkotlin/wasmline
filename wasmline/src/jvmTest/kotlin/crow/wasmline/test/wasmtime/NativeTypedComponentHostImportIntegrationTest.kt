package crow.wasmline.test.wasmtime

import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineComponentCallResult
import crow.wasmline.WasmlineComponentExport
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
import crow.wasmline.component
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invokeComponentResult
import crow.wasmline.platformWasmlineLoadArtifact
import crow.wasmline.platformWasmlineRuntimeCapabilities
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verifies native linking for synchronous typed Component Model host imports.
 *
 * Validates typed Component JNI registry dispatch with generated AOT artifacts.
 *
 * Date: 2026-09-01
 * Author: crowforkotlin
 */
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
                    failure.failure.code.isComponentCallFailure(),
                    "Expected the native Component callback failure, got ${failure.failure.code}.",
                )
                assertContains(
                    failure.failure.message,
                    "No typed Component host adapter is registered for 'example:host/api/increment'.",
                )
            } finally {
                handle.close()
            }
        } finally {
            WasmlineRuntime.shutdown()
            artifact.delete()
        }
    }

    @Test
    fun typedHostImportRoundTripsThroughTheJniRegistry() {
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
            WasmlineRuntime.shutdown()
            artifact.delete()
        }
    }

    @Test
    fun facadeCreatesIsolatedInstancesFromOneLoadedModule() {
        val artifact = copyFixture()
        try {
            val handle = loadComponent(artifact)
            try {
                val module = handle.component()
                val first = module.instantiate { bindImports(typedHostRegistry(1)) }
                val second = module.instantiate { bindImports(typedHostRegistry(100)) }
                val run = WasmlineComponentExport.root("run")
                try {
                    assertEquals(42, first.invoke(run, listOf(WasmlineComponentValue.S32(41))).singleS32())
                    assertEquals(141, second.invoke(run, listOf(WasmlineComponentValue.S32(41))).singleS32())
                    first.close()
                    assertEquals(142, second.invoke(run, listOf(WasmlineComponentValue.S32(42))).singleS32())
                } finally {
                    first.close()
                    second.close()
                }
            } finally {
                handle.close()
            }
        } finally {
            WasmlineRuntime.shutdown()
            artifact.delete()
        }
    }

    private fun typedHostRegistry(increment: Int = 1): WasmlineComponentHostRegistry {
        val interfaceId = WasmlineComponentInterfaceId.of("example:host/api")
        val functionId = WasmlineComponentFunctionId.of(interfaceId, "increment")
        return WasmlineComponentHostRegistry.builder()
            .register(
                functionId,
                WasmlineComponentHostAdapter { arguments ->
                    val value = assertIs<WasmlineComponentValue.S32>(arguments.single()).value
                    WasmlineCallResult.Success(listOf(WasmlineComponentValue.S32(value + increment)))
                },
            )
            .build()
    }

    private fun WasmlineCallResult<WasmlineComponentCallResult>.singleS32(): Int = assertIs<WasmlineComponentValue.S32>(
        assertIs<WasmlineCallResult.Success<WasmlineComponentCallResult>>(this).value.values.single(),
    ).value

    private fun loadComponent(artifact: File): crow.wasmline.Wasmline {
        val artifactFormat = componentAotFormat(artifact.name)
        val runtime = platformWasmlineRuntimeCapabilities()
        val state = platformWasmlineLoadArtifact(
            descriptor = nativeTestArtifactDescriptor(
                path = artifact.absolutePath,
                artifactFormat = artifactFormat,
                runtime = runtime,
                executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
                invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
                exportName = "run",
            ),
            config = WasmlineConfig(supportConcurrent = false),
        )
        return assertIs<WasmlineLoadState.Success>(state).wasmline
    }

    private fun copyFixture(): File = NativeFixtureTestSupport.copy("component-typed-host")

    private fun componentAotFormat(filename: String): WasmlineArtifactFormat = when {
        filename.endsWith(".cwasm", ignoreCase = true) -> WasmlineArtifactFormat.CWASM

        filename.endsWith(".pwasm", ignoreCase = true) -> WasmlineArtifactFormat.PWASM

        else -> throw IllegalArgumentException(
            "Typed Component host fixture must be a precompiled .cwasm or .pwasm artifact, not '$filename'.",
        )
    }

    private fun WasmlineErrorCode.isComponentCallFailure(): Boolean =
        this == WasmlineErrorCode.COMPONENT_CALL_FAILED || this == WasmlineErrorCode.COMPONENT_TRAP
}
