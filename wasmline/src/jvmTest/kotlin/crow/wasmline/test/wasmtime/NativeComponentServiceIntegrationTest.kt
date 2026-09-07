package crow.wasmline.test.wasmtime

import crow.wasmline.Wasmline
import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineComponentServiceContract
import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.WasmlineLoadState
import crow.wasmline.WasmlineRuntime
import crow.wasmline.callResult
import crow.wasmline.internal.bridge.WasmlineHostDispatcher
import crow.wasmline.internal.protocol.WasmlineResponseCodec
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.platformWasmlineLoadArtifact
import crow.wasmline.platformWasmlineRuntimeCapabilities
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Exercises the wasmline:service Component callback boundary through JNI and Wasmtime.
 *
 * Date: 2026-09-01
 * Author: crowforkotlin
 */
class NativeComponentServiceIntegrationTest {
    @Test
    fun componentServiceFixturesRequirePrecompiledAotSuffixes() {
        assertEquals(WasmlineArtifactFormat.CWASM, componentAotFormat("fixture.cwasm"))
        assertEquals(WasmlineArtifactFormat.PWASM, componentAotFormat("fixture.pwasm"))
        assertFailsWith<IllegalArgumentException> {
            componentAotFormat("fixture.wasm")
        }
    }

    @Test
    fun componentCallsBoundHostHandler() {
        val artifact = copyFixture()
        val handle = loadComponent(artifact)
        var callbackAction: String? = null
        var callbackPayload: ByteArray? = null
        val response = byteArrayOf(9, 8, 7)
        try {
            handle.setOutbound(
                WasmlineHostDispatcher { action, payload ->
                    callbackAction = action
                    callbackPayload = payload.copyOf()
                    WasmlineResponseCodec.encodeSuccess(response)
                },
            )

            val request = byteArrayOf(1, 2, 3)
            val result = assertIs<WasmlineCallResult.Success<ByteArray>>(
                handle.callResult(ACTION_CALLBACK, request),
            )

            assertEquals(HOST_CALLBACK_ACTION, callbackAction)
            assertContentEquals(request, callbackPayload)
            assertContentEquals(response, result.value)
        } finally {
            handle.close()
            WasmlineRuntime.shutdown()
            artifact.delete()
        }
    }

    @Test
    fun componentCallbackWithoutHandlerReturnsActionNotBound() {
        val artifact = copyFixture()
        val handle = loadComponent(artifact)
        try {
            val result = assertIs<WasmlineCallResult.Failure>(
                handle.callResult(ACTION_CALLBACK, byteArrayOf(1)),
            )

            assertEquals(WasmlineErrorCode.ACTION_NOT_BOUND, result.failure.code)
        } finally {
            handle.close()
            WasmlineRuntime.shutdown()
            artifact.delete()
        }
    }

    @Test
    fun sameComponentReentryReturnsRecoverableFailure() {
        val artifact = copyFixture()
        val handle = loadComponent(artifact)
        try {
            handle.setOutbound(
                WasmlineHostDispatcher { _, payload ->
                    when (val nested = handle.callResult(ACTION_ECHO, payload)) {
                        is WasmlineCallResult.Success -> WasmlineResponseCodec.encodeSuccess(nested.value)
                        is WasmlineCallResult.Failure -> WasmlineResponseCodec.encodeFailure(nested.failure)
                    }
                },
            )

            val result = assertIs<WasmlineCallResult.Failure>(
                handle.callResult(ACTION_CALLBACK, byteArrayOf(4, 5, 6)),
            )

            assertEquals(WasmlineErrorCode.COMPONENT_CALL_FAILED, result.failure.code)
            assertEquals("Recursive invocation of the same Component session is not supported.", result.failure.message)
        } finally {
            handle.close()
            WasmlineRuntime.shutdown()
            artifact.delete()
        }
    }

    @Test
    fun callbackCanInvokeAnotherComponentSession() {
        val outerArtifact = copyFixture()
        val nestedArtifact = copyFixture()
        val outer = loadComponent(outerArtifact)
        val nested = loadComponent(nestedArtifact)
        try {
            outer.setOutbound(
                WasmlineHostDispatcher { _, payload ->
                    when (val result = nested.callResult(ACTION_ECHO, payload)) {
                        is WasmlineCallResult.Success -> WasmlineResponseCodec.encodeSuccess(result.value)
                        is WasmlineCallResult.Failure -> WasmlineResponseCodec.encodeFailure(result.failure)
                    }
                },
            )

            val request = byteArrayOf(7, 0, -1)
            val result = assertIs<WasmlineCallResult.Success<ByteArray>>(
                outer.callResult(ACTION_CALLBACK, request),
            )

            assertContentEquals(request, result.value)
        } finally {
            outer.close()
            nested.close()
            WasmlineRuntime.shutdown()
            outerArtifact.delete()
            nestedArtifact.delete()
        }
    }

    private fun loadComponent(artifact: File): Wasmline {
        val artifactFormat = componentAotFormat(artifact.name)
        val runtime = platformWasmlineRuntimeCapabilities()
        val state = platformWasmlineLoadArtifact(
            descriptor = nativeTestArtifactDescriptor(
                path = artifact.absolutePath,
                artifactFormat = artifactFormat,
                runtime = runtime,
                executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
                invocationProtocol = WasmlineInvocationProtocol.WASMLINE_SERVICE,
                exportName = WasmlineComponentServiceContract.DEFAULT_EXPORT,
                contractMetadata = mapOf(
                    WasmlineComponentServiceContract.METADATA_PROFILE to WasmlineComponentServiceContract.PROFILE,
                    WasmlineComponentServiceContract.METADATA_CODEC to WasmlineComponentServiceContract.DEFAULT_CODEC,
                    WasmlineComponentServiceContract.METADATA_VERSION to WasmlineComponentServiceContract.VERSION,
                ),
            ),
            config = WasmlineConfig(supportConcurrent = false),
        )
        return assertIs<WasmlineLoadState.Success>(state).wasmline
    }

    private fun copyFixture(): File = NativeFixtureTestSupport.copy("component-service")

    private fun componentAotFormat(filename: String): WasmlineArtifactFormat = when {
        filename.endsWith(".cwasm", ignoreCase = true) -> WasmlineArtifactFormat.CWASM

        filename.endsWith(".pwasm", ignoreCase = true) -> WasmlineArtifactFormat.PWASM

        else -> throw IllegalArgumentException(
            "Wasmline Service fixture must be a precompiled .cwasm or .pwasm artifact, not '$filename'.",
        )
    }

    private companion object {
        const val ACTION_ECHO = "sample.echo"
        const val ACTION_CALLBACK = "sample.callback"
        const val HOST_CALLBACK_ACTION = "sample.host.callback"
    }
}
