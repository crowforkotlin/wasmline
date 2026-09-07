package crow.wasmline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class WasmlineComponentHostIdentifiersTest {
    @Test
    fun preservesExactComponentImportAndFunctionText() {
        val interfaceId = WasmlineComponentInterfaceId.of("example:host/api@1.0.0")
        val functionId = WasmlineComponentFunctionId.of(interfaceId, "invoke-callback")

        assertEquals("example:host/api@1.0.0", interfaceId.value)
        assertEquals("invoke-callback", functionId.functionName)
        assertEquals("example:host/api@1.0.0/invoke-callback", functionId.toString())
    }

    @Test
    fun rejectsBlankAndWhitespaceIdentifiersWithoutNormalizingThem() {
        listOf("", " ", " example:host/api", "example:host/api ", "example:host /api").forEach { value ->
            assertFailsWith<IllegalArgumentException> {
                WasmlineComponentInterfaceId.of(value)
            }
        }

        val interfaceId = WasmlineComponentInterfaceId.of("example:host/api")
        listOf("", " ", " invoke", "invoke ", "invoke callback").forEach { value ->
            assertFailsWith<IllegalArgumentException> {
                WasmlineComponentFunctionId.of(interfaceId, value)
            }
        }
    }

    @Test
    fun usesBothInterfaceAndFunctionTextForValueEquality() {
        val first = WasmlineComponentFunctionId.of(WasmlineComponentInterfaceId.of("example:host/a"), "invoke")
        val same = WasmlineComponentFunctionId.of(WasmlineComponentInterfaceId.of("example:host/a"), "invoke")
        val differentInterface = WasmlineComponentFunctionId.of(WasmlineComponentInterfaceId.of("example:host/b"), "invoke")
        val differentFunction = WasmlineComponentFunctionId.of(WasmlineComponentInterfaceId.of("example:host/a"), "other")

        assertEquals(first, same)
        assertNotEquals(first, differentInterface)
        assertNotEquals(first, differentFunction)
    }
}
