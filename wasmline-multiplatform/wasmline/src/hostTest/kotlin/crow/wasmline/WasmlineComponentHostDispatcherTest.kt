/**
 * Tests the typed Component Model host dispatcher.
 *
 * Date: 2026-08-07
 * Author: crowforkotlin
 */
package crow.wasmline

import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Verifies dispatch independent of JNI ownership and Component instantiation. */
class WasmlineComponentHostDispatcherTest {
    @Test
    fun dispatchesTypedArgumentsAndEncodesTypedResults() {
        val dispatcher = dispatcher { arguments ->
            val value = assertIs<WasmlineComponentValue.S32>(arguments.single()).value
            WasmlineCallResult.Success(listOf(WasmlineComponentValue.S32(value + 1)))
        }
        val arguments = assertIs<WasmlineCallResult.Success<ByteArray>>(
            WasmlineTypedInvocationCodec.encodeComponentArguments(listOf(WasmlineComponentValue.S32(41))),
        )

        val response = requireNotNull(dispatcher.dispatch("example:host/api", "increment", arguments.value))
        val result = assertIs<WasmlineCallResult.Success<WasmlineComponentCallResult>>(
            WasmlineTypedInvocationCodec.decodeComponentResult(response),
        )

        assertEquals(listOf(WasmlineComponentValue.S32(42)), result.value.values)
    }

    @Test
    fun returnsNullOnlyWhenNoAdapterIsRegistered() {
        val dispatcher = WasmlineComponentHostDispatcher(WasmlineComponentHostRegistry.builder().build())
        val arguments = assertIs<WasmlineCallResult.Success<ByteArray>>(
            WasmlineTypedInvocationCodec.encodeComponentArguments(emptyList()),
        )

        assertNull(dispatcher.dispatch("example:host/api", "missing", arguments.value))
    }

    @Test
    fun returnsTypedFailureForMalformedArguments() {
        val dispatcher = dispatcher { WasmlineCallResult.Success(emptyList()) }
        val response = requireNotNull(
            dispatcher.dispatch("example:host/api", "increment", byteArrayOf(1, 0, 0, 0, 22)),
        )

        val result = assertIs<WasmlineCallResult.Failure>(WasmlineTypedInvocationCodec.decodeComponentResult(response))
        assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, result.error.code)
    }

    @Test
    fun convertsAdapterExceptionsIntoTypedFailures() {
        val dispatcher = dispatcher { throw IllegalStateException("adapter failed") }
        val arguments = assertIs<WasmlineCallResult.Success<ByteArray>>(
            WasmlineTypedInvocationCodec.encodeComponentArguments(emptyList()),
        )

        val response = requireNotNull(dispatcher.dispatch("example:host/api", "increment", arguments.value))
        val result = assertIs<WasmlineCallResult.Failure>(WasmlineTypedInvocationCodec.decodeComponentResult(response))

        assertEquals(WasmlineErrorCode.HANDLER_FAILED, result.error.code)
        assertEquals("adapter failed", result.error.message)
    }

    @Test
    fun rejectsBindingAComponentRegistryToACoreHandleBeforeJni() {
        val wasmline = Wasmline(
            moduleKey = "core-test",
            config = WasmlineConfig(),
            descriptor = WasmlineArtifactDescriptor(
                path = "/unused/plugin.cwasm",
                artifactFormat = WasmlineArtifactFormat.CWASM,
                executionModel = WasmlineExecutionModel.CORE_WASM,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            wasmline.bindComponentHost(WasmlineComponentHostRegistry.builder().build())
        }
    }

    private fun dispatcher(adapter: WasmlineComponentHostAdapter): WasmlineComponentHostDispatcher {
        val interfaceId = WasmlineComponentInterfaceId.of("example:host/api")
        val functionId = WasmlineComponentFunctionId.of(interfaceId, "increment")
        val registry = WasmlineComponentHostRegistry.builder()
            .register(functionId, adapter)
            .build()
        return WasmlineComponentHostDispatcher(registry)
    }
}
