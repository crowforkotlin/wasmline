package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineGeneratedBridge
import crow.wasmline.internal.protocol.WasmlineResponseCodec
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WasmlineHostServiceRegistryTest {
    @Test
    fun multipleGeneratedServicesMergeIntoOneDispatcher() {
        val registry = WasmlineHostServiceRegistry()

        assertTrue(registry.registerAll(bridge("example.ServiceA#one") { byteArrayOf(1) }))
        assertEquals(false, registry.registerAll(bridge("example.ServiceB#two") { byteArrayOf(2) }))

        assertContentEquals(byteArrayOf(1), success(registry.dispatcher.dispatch("example.ServiceA#one", byteArrayOf())))
        assertContentEquals(byteArrayOf(2), success(registry.dispatcher.dispatch("example.ServiceB#two", byteArrayOf())))
    }

    @Test
    fun duplicateBatchDoesNotLeavePartialHandlers() {
        val registry = WasmlineHostServiceRegistry()
        registry.registerAll(bridge("example.ServiceA#one") { byteArrayOf(1) })

        val error = assertFailsWith<IllegalStateException> {
            registry.registerAll(
                bridge(
                    "example.ServiceB#new" to { byteArrayOf(2) },
                    "example.ServiceA#one" to { byteArrayOf(3) },
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("example.ServiceA#one"))
        val unknown = decode(registry.dispatcher.dispatch("example.ServiceB#new", byteArrayOf()))
        assertEquals(WasmlineErrorCode.UNKNOWN_ACTION, assertIs<WasmlineCallResult.Failure>(unknown).failure.code)
    }

    @Test
    fun instancesAreIsolated() {
        val first = WasmlineHostServiceRegistry().apply {
            registerAll(bridge("example.First#call") { byteArrayOf(1) })
        }
        val second = WasmlineHostServiceRegistry().apply {
            registerAll(bridge("example.Second#call") { byteArrayOf(2) })
        }

        assertEquals(
            WasmlineErrorCode.UNKNOWN_ACTION,
            assertIs<WasmlineCallResult.Failure>(
                decode(first.dispatcher.dispatch("example.Second#call", byteArrayOf())),
            ).failure.code,
        )
        assertContentEquals(byteArrayOf(2), success(second.dispatcher.dispatch("example.Second#call", byteArrayOf())))
    }

    @Test
    fun generatedAndRawOwnershipModesCannotBeMixed() {
        val generated = WasmlineHostServiceRegistry().apply {
            registerAll(bridge("example.Service#call") { byteArrayOf() })
        }
        val raw = WasmlineHostServiceRegistry().apply {
            registerRaw { _, payload -> WasmlineCallResult.Success(payload) }
        }

        assertFailsWith<IllegalStateException> {
            generated.registerRaw { _, payload -> WasmlineCallResult.Success(payload) }
        }
        assertFailsWith<IllegalStateException> {
            raw.registerAll(bridge("example.Service#call") { byteArrayOf() })
        }
    }

    @Test
    fun firstInvocationFreezesRegistrationAndCloseDropsHandlers() {
        val registry = WasmlineHostServiceRegistry().apply {
            registerAll(bridge("example.Service#call") { byteArrayOf(1) })
        }

        registry.dispatcher.dispatch("example.Service#call", byteArrayOf())
        assertFailsWith<IllegalStateException> {
            registry.registerAll(bridge("example.Other#call") { byteArrayOf(2) })
        }

        registry.clear()
        val closed = assertIs<WasmlineCallResult.Failure>(
            decode(registry.dispatcher.dispatch("example.Service#call", byteArrayOf())),
        )
        assertEquals(WasmlineErrorCode.ACTION_NOT_BOUND, closed.failure.code)
    }

    @Test
    fun errorClassificationIsStable() {
        val empty = WasmlineHostServiceRegistry()
        assertEquals(
            WasmlineErrorCode.ACTION_NOT_BOUND,
            assertIs<WasmlineCallResult.Failure>(decode(empty.dispatcher.dispatch("missing", byteArrayOf()))).failure.code,
        )

        val registry = WasmlineHostServiceRegistry().apply {
            registerAll(bridge("example.Service#fail") { error("handler boom") })
        }
        val handlerFailure = assertIs<WasmlineCallResult.Failure>(
            decode(registry.dispatcher.dispatch("example.Service#fail", byteArrayOf())),
        )
        assertEquals(WasmlineErrorCode.HANDLER_FAILED, handlerFailure.failure.code)
        assertEquals("handler boom", handlerFailure.failure.message)
    }

    private fun bridge(action: String, handler: (ByteArray) -> ByteArray): WasmlineGeneratedBridge = bridge(action to handler)

    private fun bridge(vararg actions: Pair<String, (ByteArray) -> ByteArray>): WasmlineGeneratedBridge = object : WasmlineGeneratedBridge {
        override fun invoke(action: String, payload: ByteArray): ByteArray = actions.toMap().getValue(action)(payload)

        override fun bind(registerAction: (String, (ByteArray) -> ByteArray) -> Unit) {
            actions.forEach { (action, handler) -> registerAction(action, handler) }
        }
    }

    private fun decode(bytes: ByteArray): WasmlineCallResult<ByteArray> = WasmlineResponseCodec.decodeLegacyCompatible(bytes)

    private fun success(bytes: ByteArray): ByteArray = assertIs<WasmlineCallResult.Success<ByteArray>>(decode(bytes)).value
}
