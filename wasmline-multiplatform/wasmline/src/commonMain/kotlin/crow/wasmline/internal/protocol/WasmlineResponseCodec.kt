/**
 * Encodes and decodes the Core Wasmline response frame.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
package crow.wasmline.internal.protocol

import crow.wasmline.invocation.WasmlineCallError
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode

internal object WasmlineResponseCodec {
    const val FRAME_VERSION: Int = 1

    private const val HEADER_SIZE = 18
    private const val STATUS_SUCCESS = 0
    private const val STATUS_FAILURE = 1
    private val MAGIC = byteArrayOf(0x57, 0x4C, 0x4D, 0x46)

    fun encodeSuccess(payload: ByteArray): ByteArray = encode(
        status = STATUS_SUCCESS,
        errorCode = 0,
        message = ByteArray(0),
        payload = payload,
    )

    fun encodeFailure(error: WasmlineCallError): ByteArray = encode(
        status = STATUS_FAILURE,
        errorCode = error.rawCode,
        message = error.message.encodeToByteArray(),
        payload = error.details ?: ByteArray(0),
    )

    fun decode(bytes: ByteArray): WasmlineCallResult<ByteArray> {
        if (bytes.isEmpty()) {
            return failure(WasmlineErrorCode.RESPONSE_MISSING, "Wasmline response is missing.")
        }
        if (bytes.size < HEADER_SIZE) {
            return failure(WasmlineErrorCode.RESPONSE_MALFORMED, "Wasmline response header is incomplete.")
        }
        if (!hasMagic(bytes)) {
            return failure(WasmlineErrorCode.RESPONSE_MALFORMED, "Wasmline response magic is invalid.")
        }

        val version = bytes[4].toInt() and 0xFF
        if (version != FRAME_VERSION) {
            return failure(
                WasmlineErrorCode.RESPONSE_UNSUPPORTED_VERSION,
                "Unsupported Wasmline response frame version: $version.",
            )
        }

        val status = bytes[5].toInt() and 0xFF
        val errorCode = readInt32(bytes, 6)
        val messageLength = readUInt32(bytes, 10)
        val payloadLength = readUInt32(bytes, 14)
        val bodyLength = bytes.size - HEADER_SIZE

        if (messageLength + payloadLength != bodyLength.toLong()) {
            return failure(WasmlineErrorCode.RESPONSE_MALFORMED, "Wasmline response lengths are invalid.")
        }

        val messageStart = HEADER_SIZE
        val messageEnd = messageStart + messageLength.toInt()
        val payloadStart = messageEnd
        val payloadEnd = payloadStart + payloadLength.toInt()
        val messageBytes = bytes.copyOfRange(messageStart, messageEnd)
        if (!messageBytes.isValidUtf8()) {
            return failure(WasmlineErrorCode.RESPONSE_MALFORMED, "Wasmline response message is not valid UTF-8.")
        }
        val message = messageBytes.decodeToString()
        val payload = bytes.copyOfRange(payloadStart, payloadEnd)

        return when (status) {
            STATUS_SUCCESS -> {
                if (errorCode != 0 || messageLength != 0L) {
                    failure(WasmlineErrorCode.RESPONSE_MALFORMED, "Successful response contains error fields.")
                } else {
                    WasmlineCallResult.Success(payload)
                }
            }

            STATUS_FAILURE -> {
                if (errorCode == 0) {
                    failure(WasmlineErrorCode.RESPONSE_MALFORMED, "Failed response has no error code.")
                } else {
                    WasmlineCallResult.Failure(
                        WasmlineCallError(
                            code = WasmlineErrorCode.fromValue(errorCode),
                            message = message,
                            details = payload,
                            rawCode = errorCode,
                        ),
                    )
                }
            }

            else -> failure(WasmlineErrorCode.RESPONSE_MALFORMED, "Wasmline response status is invalid.")
        }
    }

    fun decodeLegacyCompatible(bytes: ByteArray): WasmlineCallResult<ByteArray> {
        if (bytes.isEmpty()) return WasmlineCallResult.Success(ByteArray(0))
        return if (hasMagic(bytes)) decode(bytes) else WasmlineCallResult.Success(bytes)
    }

    private fun encode(status: Int, errorCode: Int, message: ByteArray, payload: ByteArray): ByteArray {
        val result = ByteArray(HEADER_SIZE + message.size + payload.size)
        MAGIC.copyInto(result, 0)
        result[4] = FRAME_VERSION.toByte()
        result[5] = status.toByte()
        writeInt32(result, 6, errorCode)
        writeInt32(result, 10, message.size)
        writeInt32(result, 14, payload.size)
        message.copyInto(result, HEADER_SIZE)
        payload.copyInto(result, HEADER_SIZE + message.size)
        return result
    }

    private fun hasMagic(bytes: ByteArray): Boolean = MAGIC.indices.all { bytes[it] == MAGIC[it] }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long = (bytes[offset].toLong() and 0xFF) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private fun readInt32(bytes: ByteArray, offset: Int): Int = readUInt32(bytes, offset).toInt()

    private fun writeInt32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    private fun failure(code: WasmlineErrorCode, message: String): WasmlineCallResult.Failure =
        WasmlineCallResult.Failure(WasmlineCallError(code, message))
}

private fun ByteArray.isValidUtf8(): Boolean {
    var index = 0
    while (index < size) {
        val first = this[index].toInt() and 0xFF
        when {
            first <= 0x7F -> index += 1

            first in 0xC2..0xDF -> {
                if (index + 1 >= size || !isContinuation(index + 1)) return false
                index += 2
            }

            first == 0xE0 -> {
                val secondValid = isByteInRange(index + 1, 0xA0..0xBF)
                val thirdValid = isContinuation(index + 2)
                if (!secondValid || !thirdValid) {
                    return false
                }
                index += 3
            }

            first in 0xE1..0xEF -> {
                if (index + 2 >= size || !isContinuation(index + 1) || !isContinuation(index + 2)) return false
                index += 3
            }

            first == 0xF0 -> {
                val secondValid = isByteInRange(index + 1, 0x90..0xBF)
                val thirdValid = isContinuation(index + 2)
                val fourthValid = isContinuation(index + 3)
                if (!secondValid || !thirdValid || !fourthValid) {
                    return false
                }
                index += 4
            }

            first in 0xF1..0xF3 -> {
                val secondValid = isContinuation(index + 1)
                val thirdValid = isContinuation(index + 2)
                val fourthValid = isContinuation(index + 3)
                if (!secondValid || !thirdValid || !fourthValid) {
                    return false
                }
                index += 4
            }

            first == 0xF4 -> {
                val secondValid = isByteInRange(index + 1, 0x80..0x8F)
                val thirdValid = isContinuation(index + 2)
                val fourthValid = isContinuation(index + 3)
                if (!secondValid || !thirdValid || !fourthValid) {
                    return false
                }
                index += 4
            }

            else -> return false
        }
    }
    return true
}

private fun ByteArray.isContinuation(index: Int): Boolean {
    val value = this[index].toInt() and 0xFF
    return value in 0x80..0xBF
}

private fun ByteArray.isByteInRange(index: Int, range: IntRange): Boolean = index in indices &&
    (this[index].toInt() and 0xFF) in range
