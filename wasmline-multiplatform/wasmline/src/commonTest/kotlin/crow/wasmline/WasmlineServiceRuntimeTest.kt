package crow.wasmline

import crow.wasmline.spi.Action
import crow.wasmline.spi.MethodId
import crow.wasmline.spi.ServiceDefinition
import crow.wasmline.spi.ServiceId
import crow.wasmline.spi.WasmlineBindingScope
import crow.wasmline.spi.WasmlineEndpoint
import crow.wasmline.spi.registerServiceDefinition
import crow.wasmline.spi.unregisterServiceDefinition
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

    private object EchoServiceDefinition : ServiceDefinition<EchoService> {
        override val contract = EchoService::class
        override val serviceId = ServiceId("test.EchoService")
        private val echoAction = Action(serviceId, MethodId("echo")).value

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

    private object ConflictingEchoServiceDefinition : ServiceDefinition<EchoService> {
        override val contract = EchoService::class
        override val serviceId = ServiceId("test.EchoService.conflict")

        override fun link(endpoint: WasmlineEndpoint): EchoService {
            return object : EchoService {
                override fun echo(message: String): String = message
            }
        }

        override fun bind(implementation: EchoService, scope: WasmlineBindingScope) = Unit
    }

    private interface MissingService : WasmlineService

    @BeforeTest
    fun setUp() {
        registerServiceDefinition(EchoServiceDefinition)
    }

    @AfterTest
    fun tearDown() {
        unregisterServiceDefinition(EchoService::class)
    }

    @Test
    fun bindAndLinkRoundTripThroughLocalEndpoint() {
        val scope = WasmlineBindingScope().apply {
            bindInternal(EchoServiceImpl())
        }

        val service = scope.endpoint().linkInternal<EchoService>()
        assertEquals("echo:hello", service.echo("hello"))
    }

    @Test
    fun bindAsUsesExplicitContract() {
        val scope = WasmlineBindingScope().apply {
            bindAsInternal<EchoService>(EchoServiceImpl())
        }

        val service = scope.endpoint().linkInternal<EchoService>()
        assertEquals("echo:typed", service.echo("typed"))
    }

    @Test
    fun repeatedRegistrationOfSameDefinitionIsIdempotent() {
        registerServiceDefinition(EchoServiceDefinition)

        val service = WasmlineBindingScope().apply {
            bindInternal(EchoServiceImpl())
        }.endpoint().linkInternal<EchoService>()

        assertEquals("echo:again", service.echo("again"))
    }

    @Test
    fun conflictingRegistrationFailsFast() {
        val error = assertFailsWith<IllegalStateException> {
            registerServiceDefinition(ConflictingEchoServiceDefinition)
        }

        assertTrue(error.message.orEmpty().contains("Conflicting Wasmline service definition registration"))
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
            scopeWithMissing().linkInternal<MissingService>()
        }
        assertTrue(error.message.orEmpty().contains("No Wasmline service definition registered"))
    }

    private fun scopeWithMissing(): WasmlineEndpoint {
        return WasmlineBindingScope().endpoint()
    }
}

