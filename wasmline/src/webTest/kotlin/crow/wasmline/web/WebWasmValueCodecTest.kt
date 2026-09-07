package crow.wasmline.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Round-trip and error-path tests for [WebWasmValueCodec].
 *
 * Runs on both web targets, so the same assertions validate the js number
 * bridging and the wasmJs BigInt bridging.
 *
 * Date: 2026-07-29
 * Author: crowforkotlin
 */
class WebWasmValueCodecTest {

    @Test
    fun encodesAndDecodesI32() {
        val values = listOf(0, 1, -1, 42, Int.MIN_VALUE, Int.MAX_VALUE)
        for (value in values) {
            val decoded = WebWasmValueCodec.decode(WebWasmValueCodec.encode(WebWasmValue.I32(value)), WebWasmType.I32)
            assertEquals(WebWasmValue.I32(value), decoded)
        }
    }

    @Test
    fun encodesAndDecodesI64() {
        val values = listOf(0L, 1L, -1L, 1_000_000_007L, Long.MIN_VALUE, Long.MAX_VALUE)
        for (value in values) {
            val decoded = WebWasmValueCodec.decode(WebWasmValueCodec.encode(WebWasmValue.I64(value)), WebWasmType.I64)
            assertEquals(WebWasmValue.I64(value), decoded)
        }
    }

    @Test
    fun encodesAndDecodesF32() {
        val values = listOf(0f, 1.5f, -2.25f, 1024f)
        for (value in values) {
            val decoded = WebWasmValueCodec.decode(WebWasmValueCodec.encode(WebWasmValue.F32(value)), WebWasmType.F32)
            assertEquals(WebWasmValue.F32(value), decoded)
        }
    }

    @Test
    fun encodesAndDecodesF64() {
        val values = listOf(0.0, 3.141592653589793, -1.0E308, 2.5)
        for (value in values) {
            val decoded = WebWasmValueCodec.decode(WebWasmValueCodec.encode(WebWasmValue.F64(value)), WebWasmType.F64)
            assertEquals(WebWasmValue.F64(value), decoded)
        }
    }

    @Test
    fun decodeResultsReturnsEmptyListForEmptyTypes() {
        assertTrue(WebWasmValueCodec.decodeResults(null, emptyList()).isEmpty())
        assertTrue(WebWasmValueCodec.decodeResults(webFromI32(7), emptyList()).isEmpty())
    }

    @Test
    fun decodeResultsMapsSingleValue() {
        val results = WebWasmValueCodec.decodeResults(webFromI32(41), listOf(WebWasmType.I32))
        assertEquals(listOf(WebWasmValue.I32(41)), results)
    }

    @Test
    fun decodeResultsMapsMultiValueArray() {
        val raw = webArrayAsValue(webArrayOf(listOf(webFromI32(1), webFromF64(2.5))))
        val results = WebWasmValueCodec.decodeResults(raw, listOf(WebWasmType.I32, WebWasmType.F64))
        assertEquals(listOf(WebWasmValue.I32(1), WebWasmValue.F64(2.5)), results)
    }

    @Test
    fun decodeResultsFailsWhenValueIsMissing() {
        assertFailsWith<WebWasmException> {
            WebWasmValueCodec.decodeResults(null, listOf(WebWasmType.I32))
        }
    }

    @Test
    fun decodeResultsFailsWhenSingleValueButMultipleTypesExpected() {
        assertFailsWith<WebWasmException> {
            WebWasmValueCodec.decodeResults(webFromI32(1), listOf(WebWasmType.I32, WebWasmType.I32))
        }
    }

    @Test
    fun decodeResultsFailsOnResultCountMismatch() {
        val raw = webArrayAsValue(webArrayOf(listOf(webFromI32(1), webFromI32(2))))
        assertFailsWith<WebWasmException> {
            WebWasmValueCodec.decodeResults(raw, listOf(WebWasmType.I32, WebWasmType.I32, WebWasmType.I32))
        }
    }
}
