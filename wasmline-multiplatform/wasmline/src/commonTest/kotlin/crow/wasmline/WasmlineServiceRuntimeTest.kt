
package crow.wasmline

import crow.wasmline.internal.bridge.UnlinkedWasmlineEndpoint
import crow.wasmline.internal.bridge.WasmlineEndpoint
import crow.wasmline.internal.bridge.WasmlineGeneratedBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Verifies generated-bridge binding and endpoint failure behavior. */
class WasmlineServiceRuntimeTest {
    private interface EchoService : WasmlineService {
        fun echo(message: String): String
    }

    private class EchoServiceImpl : EchoService {
        override fun echo(message: String): String = "echo:$message"
    }

    private class EchoServiceBridge private constructor(private val endpoint: WasmlineEndpoint, private val implementation: EchoService?) :
        EchoService,
        WasmlineGeneratedBridge {
        constructor(endpoint: WasmlineEndpoint) : this(endpoint, null)

        constructor(implementation: EchoService) : this(UnlinkedWasmlineEndpoint, implementation)

        override fun echo(message: String): String = endpoint.invoke(ECHO_ACTION, message.encodeToByteArray()).decodeToString()

        override fun bind(registerAction: (String, (ByteArray) -> ByteArray) -> Unit) {
            registerAction(ECHO_ACTION) { payload ->
                invoke(ECHO_ACTION, payload)
            }
        }

        override fun invoke(action: String, payload: ByteArray): ByteArray {
            if (action != ECHO_ACTION) {
                error("Unknown Wasmline action '$action' for generated bridge $CONTRACT_ID.")
            }
            val target = implementation ?: error(
                "Generated Wasmline bridge for $CONTRACT_ID does not hold a bound implementation. " +
                    "Did the compiler plugin wire bind() correctly?",
            )
            return target.echo(payload.decodeToString()).encodeToByteArray()
        }
    }

    private class TestBindingScope {
        private val handlers = linkedMapOf<String, (ByteArray) -> ByteArray>()

        fun bind(action: String, handler: (ByteArray) -> ByteArray) {
            check(action !in handlers) { "Action '$action' is already bound in this Wasmline binding scope." }
            handlers[action] = handler
        }

        fun endpoint(): WasmlineEndpoint = object : WasmlineEndpoint {
            override fun invoke(action: String, payload: ByteArray): ByteArray {
                val handler = handlers[action] ?: error("No Wasmline action bound for '$action'.")
                return handler(payload)
            }
        }
    }

    @Test
    fun bindAndLinkRoundTripThroughLocalEndpoint() {
        val scope = TestBindingScope().apply {
            EchoServiceBridge(EchoServiceImpl()).bind { action, handler ->
                bind(action, handler)
            }
        }

        val service = EchoServiceBridge(scope.endpoint())
        assertEquals("echo:hello", service.echo("hello"))
    }

    @Test
    fun repeatedBridgeBindingIsIdempotentAcrossIndependentScopes() {
        val firstScope = TestBindingScope().apply {
            EchoServiceBridge(EchoServiceImpl()).bind { action, handler -> bind(action, handler) }
        }
        val secondScope = TestBindingScope().apply {
            EchoServiceBridge(EchoServiceImpl()).bind { action, handler -> bind(action, handler) }
        }

        assertEquals("echo:again", EchoServiceBridge(firstScope.endpoint()).echo("again"))
        assertEquals("echo:again", EchoServiceBridge(secondScope.endpoint()).echo("again"))
    }

    @Test
    fun duplicateActionFailsFast() {
        val scope = TestBindingScope()
        scope.bind("sample#ping") { byteArrayOf(1) }

        val error = assertFailsWith<IllegalStateException> {
            scope.bind("sample#ping") { byteArrayOf(2) }
        }
        assertEquals(
            "Action 'sample#ping' is already bound in this Wasmline binding scope.",
            error.message,
        )
    }

    @Test
    fun unknownActionFailsFast() {
        val error = assertFailsWith<IllegalStateException> {
            EchoServiceBridge(EchoServiceImpl()).invoke("test.EchoService#missing", ByteArray(0))
        }
        assertTrue(error.message.orEmpty().contains("Unknown Wasmline action 'test.EchoService#missing'"))
    }

    @Test
    fun missingImplementationFailsFast() {
        val error = assertFailsWith<IllegalStateException> {
            EchoServiceBridge(object : WasmlineEndpoint {
                override fun invoke(action: String, payload: ByteArray): ByteArray = ByteArray(0)
            }).invoke(ECHO_ACTION, "ghost".encodeToByteArray())
        }
        assertTrue(error.message.orEmpty().contains("does not hold a bound implementation"))
    }

    @Test
    fun unlinkedEndpointFailsFast() {
        val error = assertFailsWith<IllegalStateException> {
            EchoServiceBridge(UnlinkedWasmlineEndpoint).echo("boom")
        }
        assertTrue(error.message.orEmpty().contains("is not linked to a transport endpoint"))
    }

    private companion object {
        const val CONTRACT_ID = "test.EchoService"
        const val ECHO_ACTION = "$CONTRACT_ID#echo"
    }
}
