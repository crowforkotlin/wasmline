package crow.wasmline

import crow.wasmline.invocation.WasmlineCallResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WasmlineComponentHostRegistryTest {
    @Test
    fun resolvesTheTypedAdapterByItsFullFunctionIdentifier() {
        val functionId = functionId("example:host/api", "echo")
        val adapter = WasmlineComponentHostAdapter { arguments -> WasmlineCallResult.Success(arguments) }
        val registry = WasmlineComponentHostRegistry.builder()
            .register(functionId, adapter)
            .build()

        assertEquals(1, registry.size)
        assertTrue(functionId in registry)
        assertSame(adapter, registry.lookup(functionId))
        assertNull(registry.lookup(functionId("example:host/api", "missing")))
    }

    @Test
    fun rejectsDuplicateRegistrationAndAllowsExplicitPendingRemoval() {
        val functionId = functionId("example:host/api", "echo")
        val adapter = WasmlineComponentHostAdapter { arguments -> WasmlineCallResult.Success(arguments) }
        val builder = WasmlineComponentHostRegistry.builder()
            .register(functionId, adapter)

        assertFailsWith<IllegalStateException> {
            builder.register(functionId, adapter)
        }
        assertTrue(builder.unregister(functionId))
        assertFalse(builder.unregister(functionId))
        assertFalse(functionId in builder.build())
    }

    @Test
    fun builtRegistryDoesNotChangeWhenTheBuilderChangesLater() {
        val first = functionId("example:host/api", "first")
        val second = functionId("example:host/api", "second")
        val adapter = WasmlineComponentHostAdapter { arguments -> WasmlineCallResult.Success(arguments) }
        val builder = WasmlineComponentHostRegistry.builder()
            .register(first, adapter)
        val initial = builder.build()

        builder.register(second, adapter)
        val updated = builder.build()

        assertTrue(first in initial)
        assertFalse(second in initial)
        assertTrue(second in updated)
    }

    @Test
    fun adapterReceivesAndReturnsTypedComponentValuesWithoutAnEnvelopeCodec() {
        val functionId = functionId("example:host/api", "increment")
        val registry = WasmlineComponentHostRegistry.builder()
            .register(
                functionId,
                WasmlineComponentHostAdapter { arguments ->
                    val value = assertIs<WasmlineComponentValue.S32>(arguments.single()).value
                    WasmlineCallResult.Success(listOf(WasmlineComponentValue.S32(value + 1)))
                },
            )
            .build()

        val result = registry.lookup(functionId)!!.invoke(listOf(WasmlineComponentValue.S32(41)))

        assertEquals(
            listOf(WasmlineComponentValue.S32(42)),
            assertIs<WasmlineCallResult.Success<List<WasmlineComponentValue>>>(result).value,
        )
    }

    private fun functionId(interfaceName: String, functionName: String): WasmlineComponentFunctionId =
        WasmlineComponentFunctionId.of(WasmlineComponentInterfaceId.of(interfaceName), functionName)
}
