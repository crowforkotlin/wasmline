package crow.wasmline.internal.core

import crow.wasmline.*
import crow.wasmline.internal.invocation.WasmlineTypedInvocationCodec
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineFailure

/**
 * Encodes the small, private bridge records used by native Core Wasm sessions.
 *
 * The record format is deliberately independent of Kotlin class names. It is
 * little-endian, length bounded, and shared by JNI and Kotlin/Native.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
internal object CoreWasmNativeCodec {
    private const val MAX_COUNT = 1_000_000
    private const val MAX_TEXT = 16 * 1024 * 1024

    /** Encodes imports that must be installed before native instantiation. */
    fun encodeImports(imports: Collection<RawImport>): ByteArray {
        val writer = Writer()
        writer.u32(imports.size)
        imports.forEach { import ->
            writer.text(import.module)
            writer.text(import.name)
            writer.signature(import.signature)
        }
        return writer.finish()
    }

    /** Decodes module export reflection returned by the native bridge. */
    fun decodeExports(bytes: ByteArray): WasmlineCallResult<List<RawExport>> {
        val reader = Reader(bytes)
        val count = reader.count() ?: return reader.failure()
        val exports = ArrayList<RawExport>(count)
        repeat(count) {
            val name = reader.text() ?: return reader.failure()
            val kind = when (reader.byte()) {
                0 -> RawExportKind.FUNCTION
                1 -> RawExportKind.MEMORY
                2 -> RawExportKind.GLOBAL
                3 -> RawExportKind.TABLE
                4 -> RawExportKind.UNKNOWN
                else -> return reader.failure("Native export kind is invalid.")
            }
            val hasSignature = reader.byte() ?: return reader.failure()
            if (hasSignature != 0 && hasSignature != 1) return reader.failure("Native export signature marker is invalid.")
            val signature = if (hasSignature == 1) reader.signature() ?: return reader.failure() else null
            exports += RawExport(name, kind, signature)
        }
        if (!reader.atEnd()) return reader.failure("Native export metadata has trailing bytes.")
        return WasmlineCallResult.Success(exports)
    }

    /** Decodes the failure-only carrier returned by a direct native memory operation. */
    fun decodeOperationFailure(bytes: ByteArray): WasmlineFailure =
        when (val result = WasmlineTypedInvocationCodec.decodeRawValues(bytes)) {
            is WasmlineCallResult.Failure -> result.failure

            is WasmlineCallResult.Success -> WasmlineFailure(
                WasmlineErrorCode.TRANSPORT_FAILURE,
                "Native memory operation returned an unexpected success carrier.",
            )
        }

    /**
     * Writes bounded little-endian native bridge records.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     */
    private class Writer {
        private val data = ArrayList<Byte>()
        private var valid = true

        fun byte(value: Int) {
            if (valid) data += value.toByte()
        }

        fun u32(value: Int) {
            if (value < 0) valid = false
            if (!valid) return
            repeat(4) { shift -> data += ((value ushr (shift * 8)) and 0xff).toByte() }
        }

        fun text(value: String) {
            val bytes = value.encodeToByteArray()
            if (bytes.size > MAX_TEXT) {
                valid = false
                return
            }
            u32(bytes.size)
            bytes.forEach(data::add)
        }

        fun bytes(value: ByteArray) {
            if (value.size > MAX_TEXT) {
                valid = false
                return
            }
            u32(value.size)
            value.forEach(data::add)
        }

        fun signature(signature: RawFunctionSignature) {
            u32(signature.parameters.size)
            signature.parameters.forEach { byte(it.ordinal) }
            u32(signature.results.size)
            signature.results.forEach { byte(it.ordinal) }
        }

        fun finish(): ByteArray = if (valid) ByteArray(data.size) { data[it] } else ByteArray(0)
    }

    /**
     * Reads bounded little-endian native bridge records.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     */
    private class Reader(private val bytes: ByteArray) {
        private var position = 0
        private var error: String? = null

        fun byte(): Int? {
            if (position >= bytes.size) {
                error = error ?: "Native Core Wasm metadata is truncated."
                return null
            }
            return bytes[position++].toInt() and 0xff
        }

        fun u32(): Long? {
            if (bytes.size - position < 4) {
                error = error ?: "Native Core Wasm metadata is truncated."
                return null
            }
            var value = 0L
            repeat(4) { shift -> value = value or ((bytes[position + shift].toLong() and 0xff) shl (shift * 8)) }
            position += 4
            return value
        }

        fun count(): Int? {
            val value = u32() ?: return null
            if (value > MAX_COUNT) {
                error = error ?: "Native Core Wasm metadata collection is too large."
                return null
            }
            return value.toInt()
        }

        fun text(): String? {
            val length = u32() ?: return null
            if (length > MAX_TEXT || length > bytes.size - position) {
                error = error ?: "Native Core Wasm metadata text is invalid."
                return null
            }
            val end = position + length.toInt()
            val value = bytes.copyOfRange(position, end).decodeToString()
            position = end
            return value
        }

        fun bytes(): ByteArray? {
            val length = u32() ?: return null
            if (length > MAX_TEXT || length > bytes.size - position) {
                error = error ?: "Native Core Wasm metadata bytes are invalid."
                return null
            }
            val end = position + length.toInt()
            val value = bytes.copyOfRange(position, end)
            position = end
            return value
        }

        fun signature(): RawFunctionSignature? {
            val paramsCount = count() ?: return null
            val params = ArrayList<RawValueType>(paramsCount)
            repeat(paramsCount) { params += type() ?: return null }
            val resultsCount = count() ?: return null
            val results = ArrayList<RawValueType>(resultsCount)
            repeat(resultsCount) { results += type() ?: return null }
            return RawFunctionSignature(params, results)
        }

        private fun type(): RawValueType? = when (val tag = byte()) {
            0 -> RawValueType.I32

            1 -> RawValueType.I64

            2 -> RawValueType.F32

            3 -> RawValueType.F64

            else -> {
                error = error ?: "Native Core Wasm value type '$tag' is unsupported."
                null
            }
        }

        fun atEnd(): Boolean = position == bytes.size

        fun failure(message: String = error ?: "Native Core Wasm metadata is invalid."): WasmlineCallResult.Failure =
            WasmlineCallResult.Failure(
                crow.wasmline.invocation.WasmlineFailure(WasmlineErrorCode.INVALID_PAYLOAD, message),
            )
    }
}
