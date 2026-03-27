package crow.wasmline

import crow.wasmline.internal.bridge.WasmlineBindingScope
import crow.wasmline.internal.bridge.registerGeneratedService
import crow.wasmline.internal.bridge.unregisterGeneratedService
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

    private val echoAction = "test.EchoService#echo"

    private interface MissingService : WasmlineService

    @BeforeTest
    fun setUp() {
        registerGeneratedService(
            contract = EchoService::class,
            serviceId = "test.EchoService",
            linker = { invokeAction ->
                object : EchoService {
                    override fun echo(message: String): String =
                        invokeAction(echoAction, message.encodeToByteArray()).decodeToString()
                }
            },
            binder = { implementation, registerAction ->
                registerAction(echoAction) { payload ->
                    implementation.echo(payload.decodeToString()).encodeToByteArray()
                }
            },
            identityTag = "test.runtime.EchoServiceDefinition",
        )
    }

    @AfterTest
    fun tearDown() {
        unregisterGeneratedService(EchoService::class)
    }

    @Test
    fun bindAndLinkRoundTripThroughLocalEndpoint() {
        val scope = WasmlineBindingScope().apply {
            bindInternal(EchoServiceImpl()) { action, handler ->
                bind(action, handler)
            }
        }

        val service = linkInternal(EchoService::class) { action, payload -> scope.invoke(action, payload) }
        assertEquals("echo:hello", service.echo("hello"))
    }

    @Test
    fun bindAsUsesExplicitContract() {
        val scope = WasmlineBindingScope().apply {
            bindInternal(EchoService::class, EchoServiceImpl()) { action, handler ->
                bind(action, handler)
            }
        }

        val service = linkInternal(EchoService::class) { action, payload -> scope.invoke(action, payload) }
        assertEquals("echo:typed", service.echo("typed"))
    }

    @Test
    fun repeatedRegistrationOfSameDefinitionIsIdempotent() {
        registerGeneratedService(
            contract = EchoService::class,
            serviceId = "test.EchoService",
            linker = { invokeAction ->
                object : EchoService {
                    override fun echo(message: String): String =
                        invokeAction(echoAction, message.encodeToByteArray()).decodeToString()
                }
            },
            binder = { implementation, registerAction ->
                registerAction(echoAction) { payload ->
                    implementation.echo(payload.decodeToString()).encodeToByteArray()
                }
            },
            identityTag = "test.runtime.EchoServiceDefinition",
        )

        val service = WasmlineBindingScope().apply {
            bindInternal(EchoServiceImpl()) { action, handler ->
                bind(action, handler)
            }
        }.let { scope ->
            linkInternal(EchoService::class) { action, payload -> scope.invoke(action, payload) }
        }

        assertEquals("echo:again", service.echo("again"))
    }

    @Test
    fun conflictingRegistrationFailsFast() {
        val error = assertFailsWith<IllegalStateException> {
            registerGeneratedService(
                contract = EchoService::class,
                serviceId = "test.EchoService.conflict",
                linker = { _ -> object : EchoService { override fun echo(message: String): String = message } },
                binder = { _, _ -> Unit },
                identityTag = "test.runtime.ConflictingEchoServiceDefinition",
            )
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
            linkInternal(MissingService::class) { _, _ -> ByteArray(0) }
        }
        assertTrue(error.message.orEmpty().contains("No Wasmline service definition registered"))
    }
}

