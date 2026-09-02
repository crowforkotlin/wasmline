package crow.wasmline

import crow.wasmline.internal.invocation.WasmlineTypedInvocationCodec
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineFailure
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Verifies the typed invocation carrier codec for raw and component values.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
class WasmlineTypedInvocationCodecTest {
    @Test
    fun encodesRawArgumentsWithLittleEndianValues() {
        val result = assertIs<WasmlineCallResult.Success<ByteArray>>(
            WasmlineTypedInvocationCodec.encodeRawArguments(
                listOf(RawValue.I32(-1), RawValue.I64(2), RawValue.F32(1.5f), RawValue.F64(2.5)),
            ),
        )

        assertContentEquals(
            byteArrayOf(
                4, 0, 0, 0,
                0, -1, -1, -1, -1,
                1, 2, 0, 0, 0, 0, 0, 0, 0,
                2, 0, 0, -64, 63,
                3, 0, 0, 0, 0, 0, 0, 4, 64,
            ),
            result.value,
        )
    }

    /** Round-trips all raw scalar value kinds through a successful response. */
    @Test
    fun decodesRawSuccessValues() {
        val values = listOf(
            RawValue.I32(-7),
            RawValue.I64(9_000_000_001L),
            RawValue.F32(-1.25f),
            RawValue.F64(3.5),
        )
        val encoded = assertIs<WasmlineCallResult.Success<ByteArray>>(
            WasmlineTypedInvocationCodec.encodeRawArguments(values),
        )

        val decoded = assertIs<WasmlineCallResult.Success<WasmlineRawCallResult>>(
            WasmlineTypedInvocationCodec.decodeRawResult(successResponse(1, encoded.value)),
        )

        assertEquals(values, decoded.value.values)
    }

    @Test
    fun decodesComponentValuesIncludingEmptyOption() {
        val values = listOf(
            WasmlineComponentValue.Bool(true),
            WasmlineComponentValue.S16(-12),
            WasmlineComponentValue.U64(42UL),
            WasmlineComponentValue.StringValue("hello"),
            WasmlineComponentValue.ListValue(listOf(WasmlineComponentValue.U8(7u))),
            WasmlineComponentValue.RecordValue(
                listOf(WasmlineComponentValue.RecordField("value", WasmlineComponentValue.S32(9))),
            ),
            WasmlineComponentValue.OptionValue(),
            WasmlineComponentValue.ResultValue(isOk = false),
            WasmlineComponentValue.MapValue(
                listOf(
                    WasmlineComponentValue.MapEntry(
                        WasmlineComponentValue.StringValue("key"),
                        WasmlineComponentValue.Bool(false),
                    ),
                ),
            ),
        )
        val arguments = assertIs<WasmlineCallResult.Success<ByteArray>>(
            WasmlineTypedInvocationCodec.encodeComponentArguments(values),
        )

        val result = assertIs<WasmlineCallResult.Success<WasmlineComponentCallResult>>(
            WasmlineTypedInvocationCodec.decodeComponentResult(successResponse(2, arguments.value)),
        )

        assertEquals(values, result.value.values)
    }

    @Test
    fun decodesComponentHostArguments() {
        val values = listOf(
            WasmlineComponentValue.StringValue("host"),
            WasmlineComponentValue.TupleValue(
                listOf(WasmlineComponentValue.S32(-2), WasmlineComponentValue.OptionValue()),
            ),
        )
        val encoded = assertIs<WasmlineCallResult.Success<ByteArray>>(
            WasmlineTypedInvocationCodec.encodeComponentArguments(values),
        )

        val decoded = assertIs<WasmlineCallResult.Success<List<WasmlineComponentValue>>>(
            WasmlineTypedInvocationCodec.decodeComponentArguments(encoded.value),
        )

        assertEquals(values, decoded.value)
    }

    @Test
    fun encodesComponentHostSuccessResult() {
        val values = listOf(WasmlineComponentValue.S32(42), WasmlineComponentValue.StringValue("done"))
        val encoded = assertIs<WasmlineCallResult.Success<ByteArray>>(
            WasmlineTypedInvocationCodec.encodeComponentResult(WasmlineCallResult.Success(values)),
        )

        val decoded = assertIs<WasmlineCallResult.Success<WasmlineComponentCallResult>>(
            WasmlineTypedInvocationCodec.decodeComponentResult(encoded.value),
        )

        assertEquals(values, decoded.value.values)
    }

    @Test
    fun encodesComponentHostFailureResultWithRawCodeAndDetails() {
        val encoded = assertIs<WasmlineCallResult.Success<ByteArray>>(
            WasmlineTypedInvocationCodec.encodeComponentResult(
                WasmlineCallResult.Failure(
                    WasmlineFailure(
                        code = WasmlineErrorCode.UNKNOWN,
                        message = "host rejected call",
                        details = byteArrayOf(7, 8),
                        rawCode = 9_999,
                    ),
                ),
            ),
        )

        val decoded = assertIs<WasmlineCallResult.Failure>(
            WasmlineTypedInvocationCodec.decodeComponentResult(encoded.value),
        )

        assertEquals(WasmlineErrorCode.UNKNOWN, decoded.failure.code)
        assertEquals(9_999, decoded.failure.rawCode)
        assertEquals("host rejected call", decoded.failure.message)
        assertContentEquals(byteArrayOf(7, 8), decoded.failure.details)
    }

    @Test
    fun decodesNativeFailureWithoutThrowing() {
        val response = response(
            status = 1,
            kind = 1,
            code = WasmlineErrorCode.CORE_EXPORT_NOT_FOUND.value,
            message = "missing".encodeToByteArray(),
            details = byteArrayOf(9, 8),
            values = byteArrayOf(0, 0, 0, 0),
        )

        val result = assertIs<WasmlineCallResult.Failure>(WasmlineTypedInvocationCodec.decodeRawResult(response))
        assertEquals(WasmlineErrorCode.CORE_EXPORT_NOT_FOUND, result.failure.code)
        assertEquals("missing", result.failure.message)
        assertContentEquals(byteArrayOf(9, 8), result.failure.details)
    }

    @Test
    fun rejectsTrailingResponseBytes() {
        val response = successResponse(1, byteArrayOf(0, 0, 0, 0)) + byteArrayOf(1)

        val result = assertIs<WasmlineCallResult.Failure>(WasmlineTypedInvocationCodec.decodeRawResult(response))
        assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, result.failure.code)
    }

    @Test
    fun rejectsInvalidResponseStatusAndKind() {
        val response = successResponse(1, byteArrayOf())
        response[0] = 2
        val invalidStatus = assertIs<WasmlineCallResult.Failure>(
            WasmlineTypedInvocationCodec.decodeRawResult(response),
        )
        assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, invalidStatus.failure.code)

        val invalidKind = assertIs<WasmlineCallResult.Failure>(
            WasmlineTypedInvocationCodec.decodeRawResult(successResponse(2, byteArrayOf())),
        )
        assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, invalidKind.failure.code)
    }

    @Test
    fun rejectsTruncatedTypedResponseHeader() {
        val result = assertIs<WasmlineCallResult.Failure>(
            WasmlineTypedInvocationCodec.decodeRawResult(ByteArray(13)),
        )
        assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, result.failure.code)
    }

    @Test
    fun rejectsSuccessErrorFieldsAndFailureWithoutCode() {
        val successWithError = response(
            status = 0,
            kind = 1,
            code = WasmlineErrorCode.CORE_TRAP.value,
            message = ByteArray(0),
            details = ByteArray(0),
            values = ByteArray(0),
        )
        val successFailure = assertIs<WasmlineCallResult.Failure>(
            WasmlineTypedInvocationCodec.decodeRawResult(successWithError),
        )
        assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, successFailure.failure.code)

        val failedWithoutCode = response(
            status = 1,
            kind = 1,
            code = 0,
            message = "failed".encodeToByteArray(),
            details = ByteArray(0),
            values = ByteArray(0),
        )
        val failure = assertIs<WasmlineCallResult.Failure>(
            WasmlineTypedInvocationCodec.decodeRawResult(failedWithoutCode),
        )
        assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, failure.failure.code)
    }

    @Test
    fun rejectsTruncatedTypedMessageAndDetails() {
        val message = response(
            status = 1,
            kind = 1,
            code = WasmlineErrorCode.CORE_TRAP.value,
            message = "message".encodeToByteArray(),
            details = ByteArray(0),
            values = ByteArray(0),
        ).copyOf(14 + 3)
        val messageFailure = assertIs<WasmlineCallResult.Failure>(
            WasmlineTypedInvocationCodec.decodeRawResult(message),
        )
        assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, messageFailure.failure.code)

        val details = response(
            status = 1,
            kind = 1,
            code = WasmlineErrorCode.CORE_TRAP.value,
            message = ByteArray(0),
            details = byteArrayOf(1, 2, 3),
            values = ByteArray(0),
        ).copyOf(14 + 2)
        val detailsFailure = assertIs<WasmlineCallResult.Failure>(
            WasmlineTypedInvocationCodec.decodeRawResult(details),
        )
        assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, detailsFailure.failure.code)
    }

    @Test
    fun rejectsInvalidRawAndComponentValueTags() {
        val rawValues = byteArrayOf(1, 0, 0, 0, 4)
        val rawFailure = assertIs<WasmlineCallResult.Failure>(
            WasmlineTypedInvocationCodec.decodeRawResult(successResponse(1, rawValues)),
        )
        assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, rawFailure.failure.code)

        val componentValues = byteArrayOf(1, 0, 0, 0, 22)
        val componentFailure = assertIs<WasmlineCallResult.Failure>(
            WasmlineTypedInvocationCodec.decodeComponentResult(successResponse(2, componentValues)),
        )
        assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, componentFailure.failure.code)
    }

    /** Rejects boolean values with a marker other than zero or one. */
    @Test
    fun rejectsInvalidComponentBooleanMarker() {
        val result = assertIs<WasmlineCallResult.Failure>(
            WasmlineTypedInvocationCodec.decodeComponentResult(
                successResponse(2, byteArrayOf(1, 0, 0, 0, 0, 2)),
            ),
        )

        assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, result.failure.code)
    }

    @Test
    fun rejectsFailedResponseWithValues() {
        val response = response(
            status = 1,
            kind = 1,
            code = WasmlineErrorCode.CORE_TRAP.value,
            message = "failed".encodeToByteArray(),
            details = ByteArray(0),
            values = byteArrayOf(1, 0, 0, 0),
        )

        val result = assertIs<WasmlineCallResult.Failure>(WasmlineTypedInvocationCodec.decodeRawResult(response))
        assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, result.failure.code)
    }

    private fun successResponse(kind: Int, values: ByteArray): ByteArray = response(
        status = 0,
        kind = kind,
        code = 0,
        message = ByteArray(0),
        details = ByteArray(0),
        values = values,
    )

    private fun response(status: Int, kind: Int, code: Int, message: ByteArray, details: ByteArray, values: ByteArray): ByteArray {
        val result = ByteArray(14 + message.size + details.size + values.size)
        result[0] = status.toByte()
        result[1] = kind.toByte()
        writeInt(result, 2, code)
        writeInt(result, 6, message.size)
        writeInt(result, 10 + message.size, details.size)
        message.copyInto(result, 10)
        details.copyInto(result, 14 + message.size)
        values.copyInto(result, 14 + message.size + details.size)
        return result
    }

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }
}
