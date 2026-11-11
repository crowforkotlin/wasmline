package crow.wasmline.web

import crow.wasmline.internal.protocol.WasmlineResponseCodec
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineFailure

/**
 * Holds the inbound/outbound buffers of one in-flight Wasmline Service call.
 *
 * The buffers are per-call state: a nested call on the same module would
 * otherwise overwrite them and hand the outer guest the inner request. The
 * native session rejects that case with `REENTRANT_CALL`, so this class holds
 * the same rule and is engine-independent to stay testable.
 *
 * Date: 2026-11-11
 * Author: crowforkotlin
 */
internal class WebWasmInvocationState {

    private var action: ByteArray = EMPTY
    private var payload: ByteArray = EMPTY
    private var response: ByteArray = EMPTY
    private var overflow: ByteArray = EMPTY
    private var active = false

    /** Reports whether a call is currently on the stack. */
    val isActive: Boolean get() = active

    /**
     * Starts one call with [action] and [payload].
     *
     * @return `null` when the call started, or an encoded `REENTRANT_CALL`
     *   failure response when another call is already active.
     */
    fun begin(action: String, payload: ByteArray): ByteArray? {
        if (active) {
            return WasmlineResponseCodec.encodeFailure(
                WasmlineFailure(
                    code = WasmlineErrorCode.REENTRANT_CALL,
                    message = "Wasmline Service web module invocation cannot reenter its own module.",
                ),
            )
        }
        active = true
        this.action = action.encodeToByteArray()
        this.payload = payload
        this.response = EMPTY
        this.overflow = EMPTY
        return null
    }

    /** Finishes the active call and returns its response bytes. */
    fun end(): ByteArray {
        val result = response
        active = false
        action = EMPTY
        payload = EMPTY
        response = EMPTY
        overflow = EMPTY
        return result
    }

    /** Clears every buffer, ending any in-flight call. */
    fun reset() {
        active = false
        action = EMPTY
        payload = EMPTY
        response = EMPTY
        overflow = EMPTY
    }

    /** Returns the length of the inbound buffer selected by the bridge type tag. */
    fun inboundSize(type: Int): Int = inbound(type).size

    /**
     * Returns at most [requested] bytes of the inbound buffer selected by the type tag.
     *
     * The native bridge clamps the copy to the available bytes instead of padding,
     * so a guest that asks for more than it has receives only the real content.
     */
    fun inboundCopy(type: Int, requested: Int): ByteArray {
        require(requested >= 0) { "Wasmline bridge copy length must not be negative: $requested." }
        val source = inbound(type)
        val length = minOf(requested, source.size)
        return if (length == 0) EMPTY else source.copyOf(length)
    }

    /** Records the response bytes published by the guest. */
    fun setResponse(bytes: ByteArray) {
        response = bytes
    }

    /** Records an oversized outbound response for the slow path. */
    fun setOverflow(bytes: ByteArray) {
        overflow = bytes
    }

    /** Returns the oversized outbound response recorded for the slow path. */
    fun overflowResponse(): ByteArray = overflow

    private fun inbound(type: Int): ByteArray = if (type == 0) action else payload

    companion object {
        private val EMPTY = ByteArray(0)
    }
}
