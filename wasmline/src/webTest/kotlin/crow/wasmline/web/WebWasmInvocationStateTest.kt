package crow.wasmline.web

import crow.wasmline.internal.protocol.WasmlineResponseCodec
import crow.wasmline.invocation.WasmlineErrorCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the per-call inbound state used by the browser Wasmline Service bridge.
 *
 * Date: 2026-11-11
 * Author: crowforkotlin
 */
class WebWasmInvocationStateTest {

    @Test
    fun `rejects a nested call and keeps the outer buffers`() {
        val state = WebWasmInvocationState()
        assertNull(state.begin("outer", byteArrayOf(1, 2)))

        val rejection = assertNotNullPayload(state.begin("inner", byteArrayOf(9)))
        val decoded = assertIs<crow.wasmline.invocation.WasmlineCallResult.Failure>(
            WasmlineResponseCodec.decode(rejection),
        )
        assertEquals(WasmlineErrorCode.REENTRANT_CALL, decoded.failure.code)
        assertContentEquals(byteArrayOf(1, 2), state.inboundCopy(1, 2))
        assertContentEquals("outer".encodeToByteArray(), state.inboundCopy(0, 8))
    }

    @Test
    fun `returns the outer response after a rejected nested call`() {
        val state = WebWasmInvocationState()
        state.begin("outer", ByteArray(0))
        state.begin("inner", ByteArray(0))
        state.setResponse(byteArrayOf(7))

        assertContentEquals(byteArrayOf(7), state.end())
        assertFalse(state.isActive)
    }

    @Test
    fun `allows a new call after the outer call ends`() {
        val state = WebWasmInvocationState()
        state.begin("outer", ByteArray(0))
        state.end()

        assertNull(state.begin("next", byteArrayOf(3)))
        assertTrue(state.isActive)
        assertEquals(1, state.inboundSize(1))
    }

    @Test
    fun `clamps the inbound copy to the available bytes`() {
        val state = WebWasmInvocationState()
        state.begin("ab", byteArrayOf(1, 2, 3))

        assertContentEquals("ab".encodeToByteArray(), state.inboundCopy(0, 16))
        assertContentEquals(byteArrayOf(1, 2), state.inboundCopy(1, 2))
        assertContentEquals(ByteArray(0), state.inboundCopy(1, 0))
    }

    @Test
    fun `reports the inbound size for each buffer kind`() {
        val state = WebWasmInvocationState()
        state.begin("abc", byteArrayOf(1, 2, 3, 4))

        assertEquals(3, state.inboundSize(0))
        assertEquals(4, state.inboundSize(1))
    }

    private fun assertNotNullPayload(bytes: ByteArray?): ByteArray {
        assertTrue(bytes != null, "Expected a rejection payload for the nested call.")
        return bytes
    }
}
