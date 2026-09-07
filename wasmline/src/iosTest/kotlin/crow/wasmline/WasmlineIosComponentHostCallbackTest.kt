@file:OptIn(ExperimentalForeignApi::class)

package crow.wasmline

import crow.wasmline.internal.component.WasmlineComponentHostDispatcher
import crow.wasmline.internal.invocation.WasmlineTypedInvocationCodec
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.native.c.wasmline_free_memory
import kotlinx.cinterop.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Covers the Native callback frame and per-handle registry without loading an artifact.
 *
 * Author: crowforkotlin
 * Date: 2026-09-01
 */
class WasmlineIosComponentHostCallbackTest {

    @Test
    fun typedComponentCallbackRoundTripsTheB32Frame() {
        val interfaceId = WasmlineComponentInterfaceId.of("example:host/api")
        val functionId = WasmlineComponentFunctionId.of(interfaceId, "increment")
        val registry = WasmlineComponentHostRegistry.builder()
            .register(
                functionId,
                WasmlineComponentHostAdapter { arguments ->
                    val value = assertIs<WasmlineComponentValue.S32>(arguments.single()).value
                    WasmlineCallResult.Success(listOf(WasmlineComponentValue.S32(value + 1)))
                },
            )
            .build()
        WasmlineComponentHostCallbackRegistry.register(
            "ios-test",
            WasmlineComponentHostDispatcher(registry),
        )

        try {
            val arguments = assertIs<WasmlineCallResult.Success<ByteArray>>(
                WasmlineTypedInvocationCodec.encodeComponentArguments(
                    listOf(WasmlineComponentValue.S32(41)),
                ),
            ).value
            val response = invokeCallback("ios-test", "example:host/api", "increment", arguments)
            val decoded = assertIs<WasmlineCallResult.Success<WasmlineComponentCallResult>>(
                WasmlineTypedInvocationCodec.decodeComponentResult(requireNotNull(response)),
            )
            assertEquals(listOf(WasmlineComponentValue.S32(42)), decoded.value.values)
        } finally {
            WasmlineComponentHostCallbackRegistry.unregister("ios-test")
        }
    }

    @Test
    fun missingTypedComponentAdapterReturnsAnUnboundCallback() {
        WasmlineComponentHostCallbackRegistry.register(
            "ios-test-missing",
            WasmlineComponentHostDispatcher(WasmlineComponentHostRegistry.builder().build()),
        )

        try {
            val arguments = assertIs<WasmlineCallResult.Success<ByteArray>>(
                WasmlineTypedInvocationCodec.encodeComponentArguments(
                    listOf(WasmlineComponentValue.S32(41)),
                ),
            ).value
            assertNull(invokeCallback("ios-test-missing", "example:host/api", "increment", arguments))
        } finally {
            WasmlineComponentHostCallbackRegistry.unregister("ios-test-missing")
        }
    }

    @Test
    fun iosComponentHostRoundTripUsesGeneratedPwasmFixture() {
        val runtime = platformWasmlineRuntimeCapabilities()
        require(runtime.pointerWidth == 64) { "iOS native AOT tests require a 64-bit Pulley runtime." }
        val profileId = runtime.aotCompatibilityProfileIdsByBackend[WasmlineEngineKind.PULLEY]
            ?.singleOrNull()
            ?: error("The iOS runtime must report exactly one Pulley AOT compatibility profile.")
        val fixturePath = NativeIosFixtureCatalog.requirePwasmPath(
            fixtureId = "component-typed-host",
            profileId = profileId,
            executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
            invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
        )
        val state = platformWasmlineLoadArtifact(
            descriptor = WasmlineArtifactDescriptor(
                path = fixturePath,
                artifactFormat = WasmlineArtifactFormat.PWASM,
                architecture = "pulley${runtime.pointerWidth}",
                pointerWidth = runtime.pointerWidth,
                aotCompatibilityProfileId = profileId,
                executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
                invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
                exportName = "run",
            ),
            config = WasmlineConfig(supportConcurrent = false),
        )
        val handle = assertIs<WasmlineLoadState.Success>(state).wasmline
        try {
            handle.bindComponentHost(typedHostRegistry())
            val result = assertIs<WasmlineCallResult.Success<WasmlineComponentCallResult>>(
                handle.invokeComponentResult(
                    exportName = "run",
                    arguments = listOf(WasmlineComponentValue.S32(41)),
                ),
            )
            assertEquals(listOf(WasmlineComponentValue.S32(42)), result.value.values)
        } finally {
            handle.close()
            WasmlineRuntime.shutdown()
        }
    }

    private fun typedHostRegistry(): WasmlineComponentHostRegistry {
        val interfaceId = WasmlineComponentInterfaceId.of("example:host/api")
        val functionId = WasmlineComponentFunctionId.of(interfaceId, "increment")
        return WasmlineComponentHostRegistry.builder()
            .register(
                functionId,
                WasmlineComponentHostAdapter { arguments ->
                    val value = assertIs<WasmlineComponentValue.S32>(arguments.single()).value
                    WasmlineCallResult.Success(listOf(WasmlineComponentValue.S32(value + 1)))
                },
            )
            .build()
    }

    private fun invokeCallback(key: String, interfaceName: String, functionName: String, arguments: ByteArray): ByteArray? = memScoped {
        val outLen = alloc<ULongVar>()
        val outLenPointer = outLen.ptr
        val keyBytes = key.encodeToByteArray()
        val interfaceBytes = interfaceName.encodeToByteArray()
        val functionBytes = functionName.encodeToByteArray()
        keyBytes.usePinned { keyPinned ->
            interfaceBytes.usePinned { interfacePinned ->
                functionBytes.usePinned { functionPinned ->
                    arguments.usePinned { argumentsPinned ->
                        val response = nativeStaticComponentHostCallback(
                            keyPinned.addressOf(0),
                            keyBytes.size.toULong(),
                            interfacePinned.addressOf(0),
                            interfaceBytes.size.toULong(),
                            functionPinned.addressOf(0),
                            functionBytes.size.toULong(),
                            argumentsPinned.addressOf(0),
                            arguments.size.toULong(),
                            outLenPointer,
                        )
                        if (response == null) {
                            assertEquals(0uL, outLenPointer.pointed.value)
                            return@usePinned null
                        }
                        val result = response.readBytes(outLenPointer.pointed.value.toInt())
                        wasmline_free_memory(response)
                        result
                    }
                }
            }
        }
    }
}
