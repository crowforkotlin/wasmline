package crow.wasmline.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests driving a real `WebAssembly` engine through
 * [WebWasmRuntime] with the hand-encoded [WebTestModule] fixture.
 *
 * Covers compile, instantiate, typed export invocation (i32/i64), host
 * function imports, linear memory access, and failure paths.
 *
 * 2026-07-29
 * @author crowforkotlin
 */
class WebWasmRuntimeTest {

    @Test
    fun compileFailsOnInvalidBinary() {
        assertFailsWith<WebWasmException> {
            WebWasmRuntime.compile(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        }
    }

    @Test
    fun compileFailsOnEmptyBinary() {
        assertFailsWith<WebWasmException> {
            WebWasmRuntime.compile(ByteArray(0))
        }
    }

    @Test
    fun instantiateFailsWhenImportIsMissing() {
        val module = WebWasmRuntime.compile(WebTestModule.bytes())
        assertFailsWith<WebWasmException> {
            WebWasmRuntime.instantiate(module, WebWasmImportsBuilder())
        }
    }

    @Test
    fun invokesExportedI32Function() {
        val instance = WebTestModule.instantiate()
        val results = instance.function("add").invoke(
            args = listOf(WebWasmValue.I32(2), WebWasmValue.I32(40)),
            resultTypes = listOf(WebWasmType.I32),
        )
        assertEquals(listOf(WebWasmValue.I32(42)), results)
    }

    @Test
    fun invokesExportedI64Function() {
        val instance = WebTestModule.instantiate()
        val results = instance.function("add64").invoke(
            args = listOf(WebWasmValue.I64(3_000_000_000L), WebWasmValue.I64(-1L)),
            resultTypes = listOf(WebWasmType.I64),
        )
        assertEquals(listOf(WebWasmValue.I64(2_999_999_999L)), results)
    }

    @Test
    fun routesWasmCallToHostFunction() {
        var observedA = 0
        var observedB = 0
        val instance = WebTestModule.instantiate { a, b ->
            observedA = a
            observedB = b
            a * b
        }

        val results = instance.function("call_host").invoke(
            args = listOf(WebWasmValue.I32(6), WebWasmValue.I32(7)),
            resultTypes = listOf(WebWasmType.I32),
        )

        assertEquals(listOf(WebWasmValue.I32(42)), results)
        assertEquals(6, observedA)
        assertEquals(7, observedB)
    }

    @Test
    fun readsAndWritesLinearMemory() {
        val memory = WebTestModule.instantiate().memory()
        val payload = byteArrayOf(1, 2, 3, -1, 127, -128)

        memory.write(pointer = 16, bytes = payload)

        assertTrue(payload.contentEquals(memory.read(pointer = 16, length = payload.size)))
    }

    @Test
    fun readsUtf8TextFromLinearMemory() {
        val memory = WebTestModule.instantiate().memory()
        val text = "wasmline: 你好"
        val encoded = text.encodeToByteArray()

        memory.write(pointer = 64, bytes = encoded)

        assertEquals(text, memory.readText(pointer = 64, length = encoded.size))
    }

    @Test
    fun readsEmptyRangeAsEmptyArray() {
        val memory = WebTestModule.instantiate().memory()
        assertEquals(0, memory.read(pointer = 0, length = 0).size)
    }

    @Test
    fun missingExportsAreReportedExplicitly() {
        val instance = WebTestModule.instantiate()

        assertNull(instance.functionOrNull("does_not_exist"))
        assertNull(instance.memoryOrNull("does_not_exist"))
        assertFailsWith<WebWasmException> { instance.function("does_not_exist") }
        assertFailsWith<WebWasmException> { instance.memory("does_not_exist") }
    }

    @Test
    fun exportKindsAreValidated() {
        val instance = WebTestModule.instantiate()

        assertNull(instance.functionOrNull("memory"))
        assertNull(instance.memoryOrNull("add"))
    }
}
