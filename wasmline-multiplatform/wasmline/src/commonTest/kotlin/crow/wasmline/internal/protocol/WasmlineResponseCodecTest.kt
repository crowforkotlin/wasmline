/**
 * Tests the Core Wasmline response frame codec.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
package crow.wasmline.internal.protocol

import crow.wasmline.invocation.WasmlineCallError
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WasmlineResponseCodecTest {
    @Test
    fun encodesAndDecodesSuccessPayload() {
        val payload = "hello".encodeToByteArray()
        val frame = WasmlineResponseCodec.encodeSuccess(payload)

        assertEquals(18 + payload.size, frame.size)
        assertContentEquals(byteArrayOf(0x57, 0x4C, 0x4D, 0x46, 0x01), frame.copyOfRange(0, 5))

        val result = assertIs<WasmlineCallResult.Success<ByteArray>>(WasmlineResponseCodec.decode(frame))
        assertContentEquals(payload, result.value)
    }

    @Test
    fun encodesAndDecodesFailure() {
        val error = WasmlineCallError(
            code = WasmlineErrorCode.ACTION_NOT_BOUND,
            message = "No Wasmline action is bound.",
        )
        val frame = WasmlineResponseCodec.encodeFailure(error)

        val result = assertIs<WasmlineCallResult.Failure>(WasmlineResponseCodec.decode(frame))
        assertEquals(WasmlineErrorCode.ACTION_NOT_BOUND, result.error.code)
        assertEquals(WasmlineErrorCode.ACTION_NOT_BOUND.value, result.error.rawCode)
        assertEquals(error.message, result.error.message)
    }

    @Test
    fun rejectsUnsupportedFrameVersion() {
        val frame = WasmlineResponseCodec.encodeSuccess(ByteArray(0))
        frame[4] = 2

        val result = assertIs<WasmlineCallResult.Failure>(WasmlineResponseCodec.decode(frame))
        assertEquals(WasmlineErrorCode.RESPONSE_UNSUPPORTED_VERSION, result.error.code)
    }

    @Test
    fun rejectsAllUnsupportedFrameVersions() {
        listOf(0, 2, 255).forEach { version ->
            val frame = WasmlineResponseCodec.encodeSuccess(ByteArray(0))
            frame[4] = version.toByte()

            val result = assertIs<WasmlineCallResult.Failure>(WasmlineResponseCodec.decode(frame))
            assertEquals(WasmlineErrorCode.RESPONSE_UNSUPPORTED_VERSION, result.error.code)
        }
    }

    @Test
    fun rejectsInvalidMagic() {
        val frame = WasmlineResponseCodec.encodeSuccess(ByteArray(0))
        frame[0] = 0

        val result = assertIs<WasmlineCallResult.Failure>(WasmlineResponseCodec.decode(frame))
        assertEquals(WasmlineErrorCode.RESPONSE_MALFORMED, result.error.code)
    }

    @Test
    fun rejectsInvalidStatus() {
        val frame = WasmlineResponseCodec.encodeSuccess(ByteArray(0))
        frame[5] = 2

        val result = assertIs<WasmlineCallResult.Failure>(WasmlineResponseCodec.decode(frame))
        assertEquals(WasmlineErrorCode.RESPONSE_MALFORMED, result.error.code)
    }

    @Test
    fun rejectsInconsistentSuccessErrorFields() {
        val frame = WasmlineResponseCodec.encodeSuccess(ByteArray(0))
        frame[6] = 1

        val result = assertIs<WasmlineCallResult.Failure>(WasmlineResponseCodec.decode(frame))
        assertEquals(WasmlineErrorCode.RESPONSE_MALFORMED, result.error.code)
    }

    @Test
    fun rejectsFailureWithoutErrorCode() {
        val frame = WasmlineResponseCodec.encodeFailure(
            WasmlineCallError(WasmlineErrorCode.ACTION_NOT_BOUND, "missing"),
        )
        frame[6] = 0
        frame[7] = 0
        frame[8] = 0
        frame[9] = 0

        val result = assertIs<WasmlineCallResult.Failure>(WasmlineResponseCodec.decode(frame))
        assertEquals(WasmlineErrorCode.RESPONSE_MALFORMED, result.error.code)
    }

    @Test
    fun rejectsTruncatedHeader() {
        val result = assertIs<WasmlineCallResult.Failure>(WasmlineResponseCodec.decode(ByteArray(17)))
        assertEquals(WasmlineErrorCode.RESPONSE_MALFORMED, result.error.code)
    }

    @Test
    fun rejectsTruncatedMessage() {
        val frame = WasmlineResponseCodec.encodeFailure(
            WasmlineCallError(WasmlineErrorCode.ACTION_NOT_BOUND, "message"),
        ).copyOf(18 + 3)

        val result = assertIs<WasmlineCallResult.Failure>(WasmlineResponseCodec.decode(frame))
        assertEquals(WasmlineErrorCode.RESPONSE_MALFORMED, result.error.code)
    }

    @Test
    fun rejectsTruncatedPayload() {
        val frame = WasmlineResponseCodec.encodeFailure(
            WasmlineCallError(
                code = WasmlineErrorCode.ACTION_NOT_BOUND,
                message = "message",
                details = byteArrayOf(1, 2, 3),
            ),
        ).copyOf(18 + 7 + 2)

        val result = assertIs<WasmlineCallResult.Failure>(WasmlineResponseCodec.decode(frame))
        assertEquals(WasmlineErrorCode.RESPONSE_MALFORMED, result.error.code)
    }

    @Test
    fun rejectsLengthMismatchAndTrailingBytes() {
        val frame = WasmlineResponseCodec.encodeSuccess(byteArrayOf(1, 2)) + byteArrayOf(3)

        val result = assertIs<WasmlineCallResult.Failure>(WasmlineResponseCodec.decode(frame))
        assertEquals(WasmlineErrorCode.RESPONSE_MALFORMED, result.error.code)
    }

    @Test
    fun rejectsInvalidUtf8Message() {
        val frame = WasmlineResponseCodec.encodeFailure(
            WasmlineCallError(WasmlineErrorCode.ACTION_NOT_BOUND, "x"),
        )
        frame[18] = 0xC3.toByte()

        val result = assertIs<WasmlineCallResult.Failure>(WasmlineResponseCodec.decode(frame))
        assertEquals(WasmlineErrorCode.RESPONSE_MALFORMED, result.error.code)
    }

    @Test
    fun acceptsEmptySuccessPayload() {
        val result = assertIs<WasmlineCallResult.Success<ByteArray>>(
            WasmlineResponseCodec.decode(WasmlineResponseCodec.encodeSuccess(ByteArray(0))),
        )
        assertEquals(0, result.value.size)
    }

    @Test
    fun preservesFailureDetails() {
        val details = byteArrayOf(9, 8, 7)
        val result = assertIs<WasmlineCallResult.Failure>(
            WasmlineResponseCodec.decode(
                WasmlineResponseCodec.encodeFailure(
                    WasmlineCallError(WasmlineErrorCode.ACTION_NOT_BOUND, "missing", details),
                ),
            ),
        )

        assertContentEquals(details, result.error.details)
    }

    @Test
    fun rejectsMissingResponse() {
        val result = assertIs<WasmlineCallResult.Failure>(WasmlineResponseCodec.decode(ByteArray(0)))
        assertEquals(WasmlineErrorCode.RESPONSE_MISSING, result.error.code)
    }

    @Test
    fun keepsLegacyPayloadForCompatibility() {
        val payload = "legacy".encodeToByteArray()

        val result = assertIs<WasmlineCallResult.Success<ByteArray>>(
            WasmlineResponseCodec.decodeLegacyCompatible(payload),
        )
        assertContentEquals(payload, result.value)
    }

    @Test
    fun mapsEveryStableErrorCode() {
        val expected = mapOf(
            1001 to WasmlineErrorCode.ACTION_NOT_BOUND,
            1002 to WasmlineErrorCode.UNKNOWN_ACTION,
            1003 to WasmlineErrorCode.INVALID_PAYLOAD,
            1004 to WasmlineErrorCode.HANDLER_FAILED,
            1005 to WasmlineErrorCode.SERIALIZATION_FAILED,
            2001 to WasmlineErrorCode.ENGINE_NOT_INITIALIZED,
            2002 to WasmlineErrorCode.CORE_TRAP,
            2003 to WasmlineErrorCode.CORE_EXPORT_NOT_FOUND,
            2004 to WasmlineErrorCode.INVOCATION_PROTOCOL_MISMATCH,
            2101 to WasmlineErrorCode.COMPONENT_TRAP,
            2102 to WasmlineErrorCode.COMPONENT_EXPORT_NOT_FOUND,
            2103 to WasmlineErrorCode.COMPONENT_CALL_FAILED,
            3001 to WasmlineErrorCode.RESPONSE_MALFORMED,
            3002 to WasmlineErrorCode.RESPONSE_MISSING,
            3003 to WasmlineErrorCode.TRANSPORT_FAILURE,
            3004 to WasmlineErrorCode.RESPONSE_UNSUPPORTED_VERSION,
        )

        expected.forEach { (value, code) -> assertEquals(code, WasmlineErrorCode.fromValue(value)) }
        assertEquals(WasmlineErrorCode.UNKNOWN, WasmlineErrorCode.fromValue(0))
    }
}
