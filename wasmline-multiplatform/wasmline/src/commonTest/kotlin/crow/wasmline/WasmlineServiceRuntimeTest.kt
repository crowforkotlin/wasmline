package crow.wasmline

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WasmlineServiceRuntimeTest {
    private interface EchoService : WasmlineService {
        fun echo(message: String): String
    }

    private class EchoServiceImpl : EchoService {
        override fun echo(message: String): String = "echo:$message"
    }

    private object EchoServiceDefinition : WasmlineServiceDefinition<EchoService> {
        override val contract = EchoService::class
        override val serviceId = WasmlineServiceId("test.EchoService")
        private val echoAction = WasmlineAction(serviceId, WasmlineMethodId("echo")).value

        override fun link(endpoint: WasmlineEndpoint): EchoService {
            return object : EchoService {
                override fun echo(message: String): String =
                    endpoint.invoke(echoAction, message.encodeToByteArray()).decodeToString()
            }
        }

        override fun bind(implementation: EchoService, scope: WasmlineBindingScope) {
            scope.bind(echoAction) { payload ->
                implementation.echo(payload.decodeToString()).encodeToByteArray()
            }
        }
    }

    private interface MissingService : WasmlineService

    @BeforeTest
    fun setUp() {
        registerWasmlineServiceDefinition(EchoServiceDefinition)
    }

    @AfterTest
    fun tearDown() {
        unregisterWasmlineServiceDefinition(EchoService::class)
    }

    @Test
    fun bindAndLinkRoundTripThroughLocalEndpoint() {
        val scope = WasmlineBindingScope().apply {
            bind(EchoServiceImpl())
        }

        val service = scope.endpoint().link<EchoService>()
        assertEquals("echo:hello", service.echo("hello"))
    }

    @Test
    fun bindAsUsesExplicitContract() {
        val scope = WasmlineBindingScope().apply {
            bindAs<EchoService>(EchoServiceImpl())
        }

        val service = scope.endpoint().link<EchoService>()
        assertEquals("echo:typed", service.echo("typed"))
    }

    @Test
    fun duplicateActionFailsFast() {
        val scope = WasmlineBindingScope()
        scope.bind("sample#ping") { byteArrayOf(1) }

        val error = assertFailsWith<IllegalStateException> {
            scope.bind("sample#ping") { byteArrayOf(2) }
        }
        assertEquals(
            "Action 'sample#ping' is already bound in this Wasmline binding scope.",
            error.message
        )
    }

    @Test
    fun missingDefinitionFailsFast() {
        val error = assertFailsWith<IllegalStateException> {
            scopeWithMissing().link<MissingService>()
        }
        assertTrue(error.message.orEmpty().contains("No Wasmline service definition registered"))
    }

    private fun scopeWithMissing(): WasmlineEndpoint {
        return WasmlineBindingScope().endpoint()
    }
}

