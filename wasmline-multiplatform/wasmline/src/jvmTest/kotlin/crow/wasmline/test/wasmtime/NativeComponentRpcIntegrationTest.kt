/**
 * Exercises the wasmline:rpc Component callback boundary through JNI and Wasmtime.
 *
 * Date: 2026-08-06
 * Author: crowforkotlin
 */
package crow.wasmline.test.wasmtime

import crow.wasmline.Wasmline
import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineComponentRpcContract
import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.WasmlineLoadState
import crow.wasmline.callResult
import crow.wasmline.internal.bridge.WasmlineHostDispatcher
import crow.wasmline.internal.protocol.WasmlineResponseCodec
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.wasmlineLoadArtifact
import crow.wasmline.wasmlineRuntimeCapabilities
import crow.wasmline.wasmlineShutdown
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class NativeComponentRpcIntegrationTest {
    @Test
    fun componentRpcFixturesRequirePrecompiledAotSuffixes() {
        assertEquals(WasmlineArtifactFormat.CWASM, componentAotFormat("fixture.cwasm"))
        assertEquals(WasmlineArtifactFormat.PWASM, componentAotFormat("fixture.pwasm"))
        assertFailsWith<IllegalArgumentException> {
            componentAotFormat("fixture.wasm")
        }
    }

    @Test
    fun componentCallsBoundHostHandler() {
        if (!liveTestsEnabled()) return

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
            wasmlineShutdown()
            artifact.delete()
        }
    }

    @Test
    fun componentCallbackWithoutHandlerReturnsActionNotBound() {
        if (!liveTestsEnabled()) return

        val artifact = copyFixture()
        val handle = loadComponent(artifact)
        try {
            val result = assertIs<WasmlineCallResult.Failure>(
                handle.callResult(ACTION_CALLBACK, byteArrayOf(1)),
            )

            assertEquals(WasmlineErrorCode.ACTION_NOT_BOUND, result.error.code)
        } finally {
            handle.close()
            wasmlineShutdown()
            artifact.delete()
        }
    }

    @Test
    fun sameComponentReentryReturnsRecoverableFailure() {
        if (!liveTestsEnabled()) return

        val artifact = copyFixture()
        val handle = loadComponent(artifact)
        try {
            handle.setOutbound(
                WasmlineHostDispatcher { _, payload ->
                    when (val nested = handle.callResult(ACTION_ECHO, payload)) {
                        is WasmlineCallResult.Success -> WasmlineResponseCodec.encodeSuccess(nested.value)
                        is WasmlineCallResult.Failure -> WasmlineResponseCodec.encodeFailure(nested.error)
                    }
                },
            )

            val result = assertIs<WasmlineCallResult.Failure>(
                handle.callResult(ACTION_CALLBACK, byteArrayOf(4, 5, 6)),
            )

            assertEquals(WasmlineErrorCode.COMPONENT_CALL_FAILED, result.error.code)
            assertEquals("Recursive invocation of the same Component session is not supported.", result.error.message)
        } finally {
            handle.close()
            wasmlineShutdown()
            artifact.delete()
        }
    }

    @Test
    fun callbackCanInvokeAnotherComponentSession() {
        if (!liveTestsEnabled()) return

        val outerArtifact = copyFixture()
        val nestedArtifact = copyFixture()
        val outer = loadComponent(outerArtifact)
        val nested = loadComponent(nestedArtifact)
        try {
            outer.setOutbound(
                WasmlineHostDispatcher { _, payload ->
                    when (val result = nested.callResult(ACTION_ECHO, payload)) {
                        is WasmlineCallResult.Success -> WasmlineResponseCodec.encodeSuccess(result.value)
                        is WasmlineCallResult.Failure -> WasmlineResponseCodec.encodeFailure(result.error)
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
            wasmlineShutdown()
            outerArtifact.delete()
            nestedArtifact.delete()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun kotlinSampleEchoUsesProtobufPayload() {
        if (!liveTestsEnabled()) return

        val artifact = copyFixture(KOTLIN_COMPONENT_FIXTURE_ENV)
        val handle = loadComponent(artifact)
        try {
            val request = ProtoBuf.encodeToByteArray(
                KotlinEchoRequest.serializer(),
                KotlinEchoRequest("hello"),
            )
            val result = assertIs<WasmlineCallResult.Success<ByteArray>>(
                handle.callResult(KOTLIN_ACTION_ECHO, request),
            )
            val response = ProtoBuf.decodeFromByteArray(KotlinEchoResponse.serializer(), result.value)

            assertEquals("plugin:hello", response.value)
        } finally {
            handle.close()
            wasmlineShutdown()
            artifact.delete()
        }
    }

    private fun loadComponent(artifact: File): Wasmline {
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
                invocationProtocol = WasmlineInvocationProtocol.WASMLINE_COMPONENT_RPC,
                exportName = WasmlineComponentRpcContract.DEFAULT_EXPORT,
                contractMetadata = mapOf(
                    WasmlineComponentRpcContract.METADATA_PROFILE to WasmlineComponentRpcContract.PROFILE,
                    WasmlineComponentRpcContract.METADATA_CODEC to WasmlineComponentRpcContract.DEFAULT_CODEC,
                    WasmlineComponentRpcContract.METADATA_VERSION to WasmlineComponentRpcContract.VERSION,
                ),
            ),
            config = WasmlineConfig(supportConcurrent = false),
        )
        return assertIs<WasmlineLoadState.Success>(state).wasmline
    }

    private fun copyFixture(environmentVariable: String = COMPONENT_FIXTURE_ENV): File {
        val source = requireNotNull(System.getenv(environmentVariable)) {
            "$environmentVariable must be set when $LIVE_TESTS_ENV=1."
        }.let(::File)
        require(source.isFile) { "$environmentVariable does not point to a file: ${source.absolutePath}" }
        val suffix = componentAotFormat(source.name).fileSuffix()
        return File.createTempFile("wasmline-component-rpc-", suffix).apply {
            source.copyTo(this, overwrite = true)
            deleteOnExit()
        }
    }

    private fun componentAotFormat(filename: String): WasmlineArtifactFormat = when {
        filename.endsWith(".cwasm", ignoreCase = true) -> WasmlineArtifactFormat.CWASM

        filename.endsWith(".pwasm", ignoreCase = true) -> WasmlineArtifactFormat.PWASM

        else -> throw IllegalArgumentException(
            "Component RPC fixture must be a precompiled .cwasm or .pwasm artifact, not '$filename'.",
        )
    }

    private fun WasmlineArtifactFormat.fileSuffix(): String = when (this) {
        WasmlineArtifactFormat.CWASM -> ".cwasm"
        WasmlineArtifactFormat.PWASM -> ".pwasm"
        WasmlineArtifactFormat.RAW_WASM -> error("Component RPC fixtures cannot use raw Wasm.")
    }

    private fun targetCpuFor(artifactFormat: WasmlineArtifactFormat, is64Bit: Boolean, runtimeCpu: String): String = when (artifactFormat) {
        WasmlineArtifactFormat.CWASM -> runtimeCpu
        WasmlineArtifactFormat.PWASM -> if (is64Bit) "pulley64" else "pulley32"
        WasmlineArtifactFormat.RAW_WASM -> error("Component RPC fixtures cannot use raw Wasm.")
    }

    private fun targetOsFor(artifactFormat: WasmlineArtifactFormat, runtimeOs: String): String? = when (artifactFormat) {
        WasmlineArtifactFormat.CWASM -> runtimeOs
        WasmlineArtifactFormat.PWASM -> null
        WasmlineArtifactFormat.RAW_WASM -> error("Component RPC fixtures cannot use raw Wasm.")
    }

    private fun liveTestsEnabled(): Boolean = System.getenv(LIVE_TESTS_ENV) == "1"

    @Serializable
    private data class KotlinEchoRequest(val value: String)

    @Serializable
    private data class KotlinEchoResponse(val value: String)

    private companion object {
        const val LIVE_TESTS_ENV = "WASMLINE_LIVE_TESTS"
        const val COMPONENT_FIXTURE_ENV = "WASMLINE_TEST_COMPONENT_RPC"
        const val KOTLIN_COMPONENT_FIXTURE_ENV = "WASMLINE_TEST_KOTLIN_COMPONENT_RPC"
        const val ACTION_ECHO = "sample.echo"
        const val ACTION_CALLBACK = "sample.callback"
        const val HOST_CALLBACK_ACTION = "sample.host.callback"
        const val KOTLIN_ACTION_ECHO = "crow.wasmline.sample.component.ComponentPluginService#echo"
    }
}
