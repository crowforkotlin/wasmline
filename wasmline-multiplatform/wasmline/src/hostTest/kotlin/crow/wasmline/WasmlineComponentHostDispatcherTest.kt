package crow.wasmline

import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the typed Component Model host dispatcher.
 *
 * Verifies dispatch independent of JNI ownership and Component instantiation.
 *
 * Date: 2026-08-07
 * Author: crowforkotlin
 */
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
    fun dispatchesImportedResourceMethodsByTrustedIdentityAndDropsExactlyOnce() {
        val resourceId = WasmlineComponentResourceId(
            WasmlineComponentInterfaceId.of("example:host/callbacks"),
            "callback",
        )
        var dropCount = 0
        val registry = WasmlineComponentHostRegistry.builder()
            .registerResource(
                resourceId,
                WasmlineComponentHostResourceBinding(
                    methods = mapOf(
                        "call" to { implementation, arguments ->
                            val prefix = implementation as String
                            val input = assertIs<WasmlineComponentValue.StringValue>(arguments.single()).value
                            WasmlineCallResult.Success(listOf(WasmlineComponentValue.StringValue(prefix + input)))
                        },
                    ),
                    drop = { dropCount++ },
                ),
            )
            .build()
        val dispatcher = WasmlineComponentHostDispatcher(registry)
        val representation = dispatcher.createResource(resourceId, "host:")
        val owned = resource("instance-a", generation = 7u, WasmlineComponentResourceOwnership.OWN)
        dispatcher.bindResourceReference(representation, owned)

        val response = requireNotNull(
            dispatcher.dispatch(
                resourceId.interfaceId.value,
                "[method]callback.call",
                encodeArguments(
                    resource("instance-a", generation = 7u, WasmlineComponentResourceOwnership.BORROW),
                    WasmlineComponentValue.StringValue("value"),
                ),
            ),
        )
        val success = assertIs<WasmlineCallResult.Success<WasmlineComponentCallResult>>(
            WasmlineTypedInvocationCodec.decodeComponentResult(response),
        )
        assertEquals(listOf(WasmlineComponentValue.StringValue("host:value")), success.value.values)
        assertEquals(1, dispatcher.activeResourceCount())

        val staleResponse = requireNotNull(
            dispatcher.dispatch(
                resourceId.interfaceId.value,
                "[method]callback.call",
                encodeArguments(resource("instance-a", generation = 8u, WasmlineComponentResourceOwnership.BORROW)),
            ),
        )
        val stale = assertIs<WasmlineCallResult.Failure>(WasmlineTypedInvocationCodec.decodeComponentResult(staleResponse))
        assertEquals(WasmlineErrorCode.COMPONENT_RESOURCE_INVALID, stale.error.code)

        val dropResponse = requireNotNull(
            dispatcher.dispatch(
                resourceId.interfaceId.value,
                "[resource-drop]callback",
                encodeArguments(WasmlineComponentValue.U32(representation)),
            ),
        )
        assertIs<WasmlineCallResult.Success<WasmlineComponentCallResult>>(
            WasmlineTypedInvocationCodec.decodeComponentResult(dropResponse),
        )
        assertEquals(1, dropCount)
        assertEquals(0, dispatcher.activeResourceCount())

        val duplicate = requireNotNull(
            dispatcher.dispatch(
                resourceId.interfaceId.value,
                "[resource-drop]callback",
                encodeArguments(WasmlineComponentValue.U32(representation)),
            ),
        )
        val duplicateFailure = assertIs<WasmlineCallResult.Failure>(WasmlineTypedInvocationCodec.decodeComponentResult(duplicate))
        assertEquals(WasmlineErrorCode.COMPONENT_RESOURCE_INVALID, duplicateFailure.error.code)
        assertEquals(1, dropCount)
    }

    @Test
    fun instanceTeardownReleasesAllRemainingHostResourceImplementations() {
        val resourceId = WasmlineComponentResourceId(WasmlineComponentInterfaceId.of("example:host/callbacks"), "callback")
        val dropped = mutableListOf<String>()
        val registry = WasmlineComponentHostRegistry.builder()
            .registerResource(
                resourceId,
                WasmlineComponentHostResourceBinding(emptyMap()) { implementation -> dropped += implementation as String },
            )
            .build()
        val dispatcher = WasmlineComponentHostDispatcher(registry)
        dispatcher.createResource(resourceId, "first")
        dispatcher.createResource(resourceId, "second")

        assertEquals(2, dispatcher.releaseResources())
        assertEquals(listOf("first", "second"), dropped)
        assertEquals(0, dispatcher.releaseResources())
        assertTrue(dispatcher.activeResourceCount() == 0)
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

    private fun encodeArguments(vararg values: WasmlineComponentValue): ByteArray = assertIs<WasmlineCallResult.Success<ByteArray>>(
        WasmlineTypedInvocationCodec.encodeComponentArguments(values.toList()),
    ).value

    private fun resource(
        instanceKey: String,
        generation: UInt,
        ownership: WasmlineComponentResourceOwnership,
    ): WasmlineComponentValue.ResourceValue = WasmlineComponentValue.ResourceValue(
        instanceKey = instanceKey,
        typeId = 3u,
        handleId = 11uL,
        generation = generation,
        ownership = ownership,
        origin = WasmlineComponentResourceOrigin.HOST,
    )
}
