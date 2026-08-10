@file:OptIn(ExperimentalForeignApi::class)

package crow.wasmline

import crow.wasmline.invocation.WasmlineCallResult
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers the iOS callback frame and per-handle registry without loading an artifact. */
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
    fun liveIosComponentHostRoundTripUsesPwasmOnly() {
        if (getenv(LIVE_TESTS_ENV)?.toKString() != "1") return
        val fixturePath = getenv(IOS_FIXTURE_ENV)?.toKString()
            ?: error("$IOS_FIXTURE_ENV must be set when $LIVE_TESTS_ENV=1.")
        assertTrue(fixturePath.endsWith(".pwasm", ignoreCase = true))

        val runtime = wasmlineRuntimeCapabilities()
        val state = wasmlineLoadArtifact(
            descriptor = WasmlineArtifactDescriptor(
                path = fixturePath,
                artifactFormat = WasmlineArtifactFormat.PWASM,
                targetCpu = "pulley64",
                targetOs = null,
                targetCompilerVersion = "wasmtime-${runtime.wasmtimeVersion}",
                is64Bit = true,
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
            wasmlineShutdown()
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
        val keyBytes = key.encodeToByteArray()
        val interfaceBytes = interfaceName.encodeToByteArray()
        val functionBytes = functionName.encodeToByteArray()
        val keyPointer = allocate(keyBytes)
        val interfacePointer = allocate(interfaceBytes)
        val functionPointer = allocate(functionBytes)
        val argumentsPointer = allocate(arguments)
        val outLen = alloc<ULongVar>()
        try {
            val response = iosStaticComponentHostCallback(
                keyPointer,
                keyBytes.size.toULong(),
                interfacePointer,
                interfaceBytes.size.toULong(),
                functionPointer,
                functionBytes.size.toULong(),
                argumentsPointer,
                arguments.size.toULong(),
                outLen.ptr,
            )
            if (response == null) {
                assertEquals(0uL, outLen.value)
                return@memScoped null
            }
            val result = response.readBytes(outLen.value.toInt())
            nativeHeap.free(response)
            result
        } finally {
            keyPointer?.let(nativeHeap::free)
            interfacePointer?.let(nativeHeap::free)
            functionPointer?.let(nativeHeap::free)
            argumentsPointer?.let(nativeHeap::free)
        }
    }

    private fun allocate(value: ByteArray): CPointer<ByteVar>? {
        if (value.isEmpty()) return null
        return nativeHeap.allocArray<ByteVar>(value.size).also { pointer ->
            value.forEachIndexed { index, byte -> pointer[index] = byte }
        }
    }

    private companion object {
        const val LIVE_TESTS_ENV = "WASMLINE_LIVE_TESTS"
        const val IOS_FIXTURE_ENV = "WASMLINE_TEST_COMPONENT_TYPED_HOST_IOS"
    }
}
