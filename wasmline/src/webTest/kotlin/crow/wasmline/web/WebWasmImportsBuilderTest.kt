package crow.wasmline.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [WebWasmImportsBuilder] and the variadic host function bridge.
 *
 * Host functions registered through the builder are invoked directly via
 * [webCallFunction], so the JS glue is validated without a wasm module.
 *
 * Date: 2026-07-29
 * Author: crowforkotlin
 */
class WebWasmImportsBuilderTest {

    @Test
    fun groupsImportsByModuleNamespace() {
        val root = WebWasmImportsBuilder()
            .rawFunction("env", "first") { null }
            .rawFunction("env", "second") { null }
            .rawFunction("wasi_snapshot_preview1", "fd_write") { null }
            .build()

        val env = assertNotNull(webObjectReadObject(root, "env"))
        assertNotNull(webObjectRead(env, "first"))
        assertNotNull(webObjectRead(env, "second"))

        val wasi = assertNotNull(webObjectReadObject(root, "wasi_snapshot_preview1"))
        assertNotNull(webObjectRead(wasi, "fd_write"))

        assertNull(webObjectReadObject(root, "unknown"))
    }

    @Test
    fun registersPrebuiltImportValues() {
        val root = WebWasmImportsBuilder()
            .value("env", "answer", webFromI32(42))
            .build()

        val env = assertNotNull(webObjectReadObject(root, "env"))
        val value = assertNotNull(webObjectRead(env, "answer"))
        assertEquals(42, webToI32(value))
    }

    @Test
    fun typedHostFunctionDecodesParamsAndEncodesResult() {
        val root = WebWasmImportsBuilder()
            .function(
                module = "env",
                name = "mul",
                paramTypes = listOf(WebWasmType.I32, WebWasmType.I32),
                resultTypes = listOf(WebWasmType.I32),
            ) { params ->
                val a = (params[0] as WebWasmValue.I32).value
                val b = (params[1] as WebWasmValue.I32).value
                listOf(WebWasmValue.I32(a * b))
            }
            .build()

        val function = assertNotNull(webObjectRead(assertNotNull(webObjectReadObject(root, "env")), "mul"))
        assertTrue(webIsFunction(function))

        val result = webCallFunction(function, webArrayOf(listOf(webFromI32(6), webFromI32(7))))
        assertEquals(42, webToI32(assertNotNull(result)))
    }

    @Test
    fun typedHostFunctionWithoutResultsReturnsNothing() {
        var invoked = false
        val root = WebWasmImportsBuilder()
            .function(
                module = "env",
                name = "notify",
                paramTypes = listOf(WebWasmType.I32),
                resultTypes = emptyList(),
            ) { params ->
                invoked = (params[0] as WebWasmValue.I32).value == 1
                emptyList()
            }
            .build()

        val function = assertNotNull(webObjectRead(assertNotNull(webObjectReadObject(root, "env")), "notify"))
        val result = webCallFunction(function, webArrayOf(listOf(webFromI32(1))))

        assertNull(result)
        assertTrue(invoked)
    }

    @Test
    fun typedHostFunctionFailsOnMissingArguments() {
        val root = WebWasmImportsBuilder()
            .function(
                module = "env",
                name = "needs_two",
                paramTypes = listOf(WebWasmType.I32, WebWasmType.I32),
                resultTypes = emptyList(),
            ) { emptyList() }
            .build()

        val function = assertNotNull(webObjectRead(assertNotNull(webObjectReadObject(root, "env")), "needs_two"))
        assertFailsWith<WebWasmException> {
            webCallFunction(function, webArrayOf(listOf(webFromI32(1))))
        }
    }

    @Test
    fun typedHostFunctionFailsOnResultCountMismatch() {
        val root = WebWasmImportsBuilder()
            .function(
                module = "env",
                name = "broken",
                paramTypes = emptyList(),
                resultTypes = listOf(WebWasmType.I32),
            ) { emptyList() }
            .build()

        val function = assertNotNull(webObjectRead(assertNotNull(webObjectReadObject(root, "env")), "broken"))
        assertFailsWith<WebWasmException> {
            webCallFunction(function, webArrayOf(emptyList()))
        }
    }

    @Test
    fun rawHostFunctionReceivesRawHandles() {
        val root = WebWasmImportsBuilder()
            .rawFunction("env", "echo") { args -> args.firstOrNull() }
            .build()

        val function = assertNotNull(webObjectRead(assertNotNull(webObjectReadObject(root, "env")), "echo"))
        val result = webCallFunction(function, webArrayOf(listOf(webFromF64(2.5))))

        assertEquals(2.5, webToF64(assertNotNull(result)))
    }
}
