/**
 * Tests the typed invocation carrier codec.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
package crow.wasmline

import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WasmlineTypedInvocationCodecTest {
    @Test
    fun encodesRawArgumentsWithLittleEndianValues() {
        val result = assertIs<WasmlineCallResult.Success<ByteArray>>(
            WasmlineTypedInvocationCodec.encodeRawArguments(
                listOf(WasmlineRawValue.I32(-1), WasmlineRawValue.I64(2), WasmlineRawValue.F32(1.5f), WasmlineRawValue.F64(2.5)),
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
        assertEquals(WasmlineErrorCode.CORE_EXPORT_NOT_FOUND, result.error.code)
        assertEquals("missing", result.error.message)
        assertContentEquals(byteArrayOf(9, 8), result.error.details)
    }

    @Test
    fun rejectsTrailingResponseBytes() {
        val response = successResponse(1, byteArrayOf(0, 0, 0, 0)) + byteArrayOf(1)

        val result = assertIs<WasmlineCallResult.Failure>(WasmlineTypedInvocationCodec.decodeRawResult(response))
        assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, result.error.code)
    }

    @Test
    fun rejectsInvalidResponseStatusAndKind() {
        val response = successResponse(1, byteArrayOf())
        response[0] = 2
        val invalidStatus = assertIs<WasmlineCallResult.Failure>(
            WasmlineTypedInvocationCodec.decodeRawResult(response),
        )
        assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, invalidStatus.error.code)

        val invalidKind = assertIs<WasmlineCallResult.Failure>(
            WasmlineTypedInvocationCodec.decodeRawResult(successResponse(2, byteArrayOf())),
        )
        assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, invalidKind.error.code)
    }

    @Test
    fun rejectsTruncatedTypedResponseHeader() {
        val result = assertIs<WasmlineCallResult.Failure>(
            WasmlineTypedInvocationCodec.decodeRawResult(ByteArray(13)),
        )
        assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, result.error.code)
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
        assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, successFailure.error.code)

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
        assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, failure.error.code)
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
        assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, messageFailure.error.code)

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
        assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, detailsFailure.error.code)
    }

    @Test
    fun rejectsInvalidRawAndComponentValueTags() {
        val rawValues = byteArrayOf(1, 0, 0, 0, 4)
        val rawFailure = assertIs<WasmlineCallResult.Failure>(
            WasmlineTypedInvocationCodec.decodeRawResult(successResponse(1, rawValues)),
        )
        assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, rawFailure.error.code)

        val componentValues = byteArrayOf(1, 0, 0, 0, 22)
        val componentFailure = assertIs<WasmlineCallResult.Failure>(
            WasmlineTypedInvocationCodec.decodeComponentResult(successResponse(2, componentValues)),
        )
        assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, componentFailure.error.code)
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
        assertEquals(WasmlineErrorCode.INVALID_PAYLOAD, result.error.code)
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
