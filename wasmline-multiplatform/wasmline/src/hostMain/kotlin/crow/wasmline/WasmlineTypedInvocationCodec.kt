/**
 * Encodes typed invocation values for native host bridges.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
package crow.wasmline

import crow.wasmline.invocation.WasmlineCallError
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode

internal object WasmlineTypedInvocationCodec {
    private const val RAW_KIND = 1
    private const val COMPONENT_KIND = 2
    private const val STATUS_SUCCESS = 0
    private const val STATUS_FAILURE = 1
    private const val MAX_COLLECTION_SIZE = 1_000_000L
    private const val MAX_STRING_SIZE = 16L * 1024L * 1024L
    private const val MAX_DEPTH = 64

    fun encodeRawArguments(values: List<WasmlineRawValue>): WasmlineCallResult<ByteArray> = encodeArguments { writer ->
        writer.count(values.size)
        values.forEach { writeRawValue(writer, it) }
    }

    fun encodeComponentArguments(values: List<WasmlineComponentValue>): WasmlineCallResult<ByteArray> = encodeArguments { writer ->
        writer.count(values.size)
        values.forEach { writeComponentValue(writer, it, 0) }
    }

    fun decodeComponentArguments(bytes: ByteArray): WasmlineCallResult<List<WasmlineComponentValue>> {
        val reader = Reader(bytes)
        val count = reader.count() ?: return reader.failure()
        val values = ArrayList<WasmlineComponentValue>(count.toInt())
        repeat(count.toInt()) {
            values += readComponentValue(reader, 0) ?: return reader.failure()
        }
        if (!reader.isAtEnd()) return malformed("Component invocation payload has trailing bytes.")
        return WasmlineCallResult.Success(values)
    }

    fun encodeComponentResult(result: WasmlineCallResult<List<WasmlineComponentValue>>): WasmlineCallResult<ByteArray> {
        val writer = Writer()
        when (result) {
            is WasmlineCallResult.Success -> {
                writer.byte(STATUS_SUCCESS)
                writer.byte(COMPONENT_KIND)
                writer.u32(0)
                writer.string("")
                writer.bytes(ByteArray(0))
                writer.count(result.value.size)
                result.value.forEach { writeComponentValue(writer, it, 0) }
            }

            is WasmlineCallResult.Failure -> {
                writer.byte(STATUS_FAILURE)
                writer.byte(COMPONENT_KIND)
                writer.u32(result.error.rawCode.toLong() and 0xFFFF_FFFFL)
                writer.string(result.error.message)
                writer.bytes(result.error.details ?: ByteArray(0))
                writer.count(0)
            }
        }
        val error = writer.error
        return if (error == null) WasmlineCallResult.Success(writer.toByteArray()) else malformed(error)
    }

    fun decodeRawResult(bytes: ByteArray): WasmlineCallResult<WasmlineRawCallResult> {
        val reader = Reader(bytes)
        val header = readHeader(reader, RAW_KIND) ?: return reader.failure()
        if (header.status == STATUS_FAILURE) {
            if (header.valueCount != 0L || !reader.isAtEnd()) return malformed("Failed raw response contains values or trailing bytes.")
            return WasmlineCallResult.Failure(header.error)
        }

        val values = ArrayList<WasmlineRawValue>(header.valueCount.toInt())
        repeat(header.valueCount.toInt()) {
            val value = readRawValue(reader) ?: return reader.failure()
            values += value
        }
        if (!reader.isAtEnd()) return malformed("Raw response has trailing bytes.")
        return WasmlineCallResult.Success(WasmlineRawCallResult(values))
    }

    fun decodeComponentResult(bytes: ByteArray): WasmlineCallResult<WasmlineComponentCallResult> {
        val reader = Reader(bytes)
        val header = readHeader(reader, COMPONENT_KIND) ?: return reader.failure()
        if (header.status == STATUS_FAILURE) {
            if (header.valueCount != 0L ||
                !reader.isAtEnd()
            ) {
                return malformed(
                    "Failed component response contains values or trailing bytes.",
                )
            }
            return WasmlineCallResult.Failure(header.error)
        }

        val values = ArrayList<WasmlineComponentValue>(header.valueCount.toInt())
        repeat(header.valueCount.toInt()) {
            val value = readComponentValue(reader, 0) ?: return reader.failure()
            values += value
        }
        if (!reader.isAtEnd()) return malformed("Component response has trailing bytes.")
        return WasmlineCallResult.Success(WasmlineComponentCallResult(values))
    }

    private fun encodeArguments(block: (Writer) -> Unit): WasmlineCallResult<ByteArray> {
        val writer = Writer()
        block(writer)
        val error = writer.error
        return if (error == null) {
            WasmlineCallResult.Success(writer.toByteArray())
        } else {
            malformed(error)
        }
    }

    private fun writeRawValue(writer: Writer, value: WasmlineRawValue) {
        when (value) {
            is WasmlineRawValue.I32 -> {
                writer.byte(0)
                writer.u32(value.value.toLong() and 0xFFFF_FFFFL)
            }

            is WasmlineRawValue.I64 -> {
                writer.byte(1)
                writer.u64(value.value.toULong())
            }

            is WasmlineRawValue.F32 -> {
                writer.byte(2)
                writer.u32(value.value.toRawBits().toLong() and 0xFFFF_FFFFL)
            }

            is WasmlineRawValue.F64 -> {
                writer.byte(3)
                writer.u64(value.value.toRawBits().toULong())
            }
        }
    }

    private fun writeComponentValue(writer: Writer, value: WasmlineComponentValue, depth: Int) {
        if (depth > MAX_DEPTH) {
            writer.fail("Typed invocation value nesting is too deep.")
            return
        }
        when (value) {
            is WasmlineComponentValue.Bool -> {
                writer.byte(0)
                writer.byte(if (value.value) 1 else 0)
            }

            is WasmlineComponentValue.S8 -> {
                writer.byte(1)
                writer.byte(value.value.toInt() and 0xFF)
            }

            is WasmlineComponentValue.U8 -> {
                writer.byte(2)
                writer.byte(value.value.toInt())
            }

            is WasmlineComponentValue.S16 -> {
                writer.byte(3)
                writer.u32(value.value.toInt().toLong() and 0xFFFF_FFFFL)
            }

            is WasmlineComponentValue.U16 -> {
                writer.byte(4)
                writer.u32(value.value.toLong())
            }

            is WasmlineComponentValue.S32 -> {
                writer.byte(5)
                writer.u32(value.value.toLong() and 0xFFFF_FFFFL)
            }

            is WasmlineComponentValue.U32 -> {
                writer.byte(6)
                writer.u32(value.value.toLong())
            }

            is WasmlineComponentValue.S64 -> {
                writer.byte(7)
                writer.u64(value.value.toULong())
            }

            is WasmlineComponentValue.U64 -> {
                writer.byte(8)
                writer.u64(value.value)
            }

            is WasmlineComponentValue.F32 -> {
                writer.byte(9)
                writer.u32(value.value.toRawBits().toLong() and 0xFFFF_FFFFL)
            }

            is WasmlineComponentValue.F64 -> {
                writer.byte(10)
                writer.u64(value.value.toRawBits().toULong())
            }

            is WasmlineComponentValue.CharValue -> {
                writer.byte(11)
                writer.u32(value.value.toLong())
            }

            is WasmlineComponentValue.StringValue -> {
                writer.byte(12)
                writer.string(value.value)
            }

            is WasmlineComponentValue.ListValue -> {
                writer.byte(13)
                writer.count(value.values.size)
                value.values.forEach { writeComponentValue(writer, it, depth + 1) }
            }

            is WasmlineComponentValue.RecordValue -> {
                writer.byte(14)
                writer.count(value.fields.size)
                value.fields.forEach {
                    writer.string(it.name)
                    writeComponentValue(writer, it.value, depth + 1)
                }
            }

            is WasmlineComponentValue.TupleValue -> {
                writer.byte(15)
                writer.count(value.values.size)
                value.values.forEach { writeComponentValue(writer, it, depth + 1) }
            }

            is WasmlineComponentValue.VariantValue -> {
                writer.byte(16)
                writer.string(value.discriminant)
                writer.byte(if (value.value == null) 0 else 1)
                value.value?.let { writeComponentValue(writer, it, depth + 1) }
            }

            is WasmlineComponentValue.EnumValue -> {
                writer.byte(17)
                writer.string(value.name)
            }

            is WasmlineComponentValue.OptionValue -> {
                writer.byte(18)
                writer.byte(if (value.value == null) 0 else 1)
                value.value?.let { writeComponentValue(writer, it, depth + 1) }
            }

            is WasmlineComponentValue.ResultValue -> {
                writer.byte(19)
                writer.byte(if (value.isOk) 1 else 0)
                writer.byte(if (value.value == null) 0 else 1)
                value.value?.let { writeComponentValue(writer, it, depth + 1) }
            }

            is WasmlineComponentValue.FlagsValue -> {
                writer.byte(20)
                writer.count(value.names.size)
                value.names.forEach(writer::string)
            }

            is WasmlineComponentValue.MapValue -> {
                writer.byte(21)
                writer.count(value.entries.size)
                value.entries.forEach {
                    writeComponentValue(writer, it.key, depth + 1)
                    writeComponentValue(writer, it.value, depth + 1)
                }
            }
        }
    }

    private fun readHeader(reader: Reader, expectedKind: Int): Header? {
        val status = reader.byte() ?: return reader.fail("Typed invocation response status is missing.")
        val kind = reader.byte() ?: return reader.fail("Typed invocation response value kind is missing.")
        val rawCode = reader.u32() ?: return reader.fail("Typed invocation response error code is missing.")
        val message = reader.string() ?: return reader.fail("Typed invocation response message is truncated.")
        val details = reader.rawBytes() ?: return reader.fail("Typed invocation response details are truncated.")
        val valueCount = reader.count() ?: return reader.fail("Typed invocation response value count is invalid.")

        if (kind != expectedKind) return reader.fail("Typed invocation response value kind is invalid.")
        if (status != STATUS_SUCCESS && status != STATUS_FAILURE) return reader.fail("Typed invocation response status is invalid.")
        if (status == STATUS_SUCCESS && (rawCode != 0L || message.isNotEmpty())) {
            return reader.fail("Successful typed invocation response contains error fields.")
        }
        if (status == STATUS_FAILURE && rawCode == 0L) {
            return reader.fail("Failed typed invocation response has no error code.")
        }

        val code = rawCode.toInt()
        return Header(
            status = status,
            valueCount = valueCount,
            error = WasmlineCallError(
                code = WasmlineErrorCode.fromValue(code),
                message = message,
                details = details,
                rawCode = code,
            ),
        )
    }

    private fun readRawValue(reader: Reader): WasmlineRawValue? = when (reader.byte()) {
        0 -> reader.u32()?.toInt()?.let(WasmlineRawValue::I32)
        1 -> reader.u64()?.toLong()?.let(WasmlineRawValue::I64)
        2 -> reader.u32()?.toInt()?.let { WasmlineRawValue.F32(Float.fromBits(it)) }
        3 -> reader.u64()?.toLong()?.let { WasmlineRawValue.F64(Double.fromBits(it)) }
        else -> null
    }

    private fun readComponentValue(reader: Reader, depth: Int): WasmlineComponentValue? {
        if (depth > MAX_DEPTH) return reader.fail("Typed invocation value nesting is too deep.")
        return when (reader.byte()) {
            0 -> reader.byte()?.takeIf { it == 0 || it == 1 }?.let { WasmlineComponentValue.Bool(it == 1) }
            1 -> reader.byte()?.toByte()?.let(WasmlineComponentValue::S8)
            2 -> reader.byte()?.toUByte()?.let(WasmlineComponentValue::U8)
            3 -> reader.u32()?.toInt()?.toShort()?.let(WasmlineComponentValue::S16)
            4 -> reader.u32()?.toInt()?.toUShort()?.let(WasmlineComponentValue::U16)
            5 -> reader.u32()?.toInt()?.let(WasmlineComponentValue::S32)
            6 -> reader.u32()?.toUInt()?.let(WasmlineComponentValue::U32)
            7 -> reader.u64()?.toLong()?.let(WasmlineComponentValue::S64)
            8 -> reader.u64()?.let(WasmlineComponentValue::U64)
            9 -> reader.u32()?.toInt()?.let { WasmlineComponentValue.F32(Float.fromBits(it)) }
            10 -> reader.u64()?.toLong()?.let { WasmlineComponentValue.F64(Double.fromBits(it)) }
            11 -> reader.u32()?.toUInt()?.let(WasmlineComponentValue::CharValue)
            12 -> reader.string()?.let(WasmlineComponentValue::StringValue)
            13 -> readValues(reader, depth).let { it?.let(WasmlineComponentValue::ListValue) }
            14 -> readRecord(reader, depth)?.let(WasmlineComponentValue::RecordValue)
            15 -> readValues(reader, depth).let { it?.let(WasmlineComponentValue::TupleValue) }
            16 -> readVariant(reader, depth)
            17 -> reader.string()?.let(WasmlineComponentValue::EnumValue)
            18 -> readOptional(reader, depth)
            19 -> readResult(reader, depth)
            20 -> readFlags(reader)?.let(WasmlineComponentValue::FlagsValue)
            21 -> readMap(reader, depth)?.let(WasmlineComponentValue::MapValue)
            else -> reader.fail("Typed invocation value tag is unknown.")
        }
    }

    private fun readValues(reader: Reader, depth: Int): List<WasmlineComponentValue>? {
        val count = reader.count() ?: return null
        val values = ArrayList<WasmlineComponentValue>(count.toInt())
        repeat(count.toInt()) {
            values += readComponentValue(reader, depth + 1) ?: return null
        }
        return values
    }

    private fun readRecord(reader: Reader, depth: Int): List<WasmlineComponentValue.RecordField>? {
        val count = reader.count() ?: return null
        val fields = ArrayList<WasmlineComponentValue.RecordField>(count.toInt())
        repeat(count.toInt()) {
            val name = reader.string() ?: return null
            val value = readComponentValue(reader, depth + 1) ?: return null
            fields += WasmlineComponentValue.RecordField(name, value)
        }
        return fields
    }

    private fun readVariant(reader: Reader, depth: Int): WasmlineComponentValue.VariantValue? {
        val name = reader.string() ?: return null
        val hasValue = reader.byte() ?: return null
        if (hasValue != 0 && hasValue != 1) return reader.fail("Variant value marker is invalid.")
        val value = if (hasValue == 1) readComponentValue(reader, depth + 1) ?: return null else null
        return WasmlineComponentValue.VariantValue(name, value)
    }

    private fun readOptional(reader: Reader, depth: Int): WasmlineComponentValue.OptionValue? {
        val hasValue = reader.byte() ?: return null
        if (hasValue != 0 && hasValue != 1) return reader.fail("Option value marker is invalid.")
        val value = if (hasValue == 1) readComponentValue(reader, depth + 1) ?: return null else null
        return WasmlineComponentValue.OptionValue(value)
    }

    private fun readResult(reader: Reader, depth: Int): WasmlineComponentValue.ResultValue? {
        val ok = reader.byte() ?: return null
        if (ok != 0 && ok != 1) return reader.fail("Result status marker is invalid.")
        val hasValue = reader.byte() ?: return null
        if (hasValue != 0 && hasValue != 1) return reader.fail("Result value marker is invalid.")
        val value = if (hasValue == 1) readComponentValue(reader, depth + 1) ?: return null else null
        return WasmlineComponentValue.ResultValue(ok == 1, value)
    }

    private fun readFlags(reader: Reader): List<String>? {
        val count = reader.count() ?: return null
        val names = ArrayList<String>(count.toInt())
        repeat(count.toInt()) { names += reader.string() ?: return null }
        return names
    }

    private fun readMap(reader: Reader, depth: Int): List<WasmlineComponentValue.MapEntry>? {
        val count = reader.count() ?: return null
        val entries = ArrayList<WasmlineComponentValue.MapEntry>(count.toInt())
        repeat(count.toInt()) {
            val key = readComponentValue(reader, depth + 1) ?: return null
            val value = readComponentValue(reader, depth + 1) ?: return null
            entries += WasmlineComponentValue.MapEntry(key, value)
        }
        return entries
    }

    private data class Header(val status: Int, val valueCount: Long, val error: WasmlineCallError)

    private class Writer {
        private var data = ByteArray(128)
        private var size = 0
        var error: String? = null
            private set

        fun byte(value: Int) {
            if (!ensure(1)) return
            data[size++] = value.toByte()
        }

        fun u32(value: Long) {
            if (!ensure(4)) return
            repeat(4) { index -> data[size++] = (value ushr (index * 8)).toByte() }
        }

        fun u64(value: ULong) {
            if (!ensure(8)) return
            repeat(8) { index -> data[size++] = (value shr (index * 8)).toByte() }
        }

        fun count(value: Int) {
            if (value.toLong() > MAX_COLLECTION_SIZE) fail("Typed invocation collection is too large.")
            u32(value.toLong())
        }

        fun string(value: String) {
            val bytes = value.encodeToByteArray()
            if (bytes.size.toLong() > MAX_STRING_SIZE) {
                fail("Typed invocation string is too large.")
                return
            }
            u32(bytes.size.toLong())
            raw(bytes)
        }

        fun bytes(value: ByteArray) {
            if (value.size.toLong() > MAX_STRING_SIZE) {
                fail("Typed invocation details are too large.")
                return
            }
            u32(value.size.toLong())
            raw(value)
        }

        private fun raw(value: ByteArray) {
            if (!ensure(value.size)) return
            value.copyInto(data, size)
            size += value.size
        }

        fun fail(message: String) {
            if (error == null) error = message
        }

        private fun ensure(additional: Int): Boolean {
            if (error != null) return false
            if (additional < 0 || size > Int.MAX_VALUE - additional) {
                fail("Typed invocation payload is too large.")
                return false
            }
            val required = size + additional
            if (required <= data.size) return true
            var capacity = data.size
            while (capacity < required) {
                if (capacity > Int.MAX_VALUE / 2) {
                    capacity = required
                    break
                }
                capacity *= 2
            }
            data = data.copyOf(capacity)
            return true
        }

        fun toByteArray(): ByteArray = data.copyOf(size)
    }

    private class Reader(private val data: ByteArray) {
        private var position = 0
        var error: String? = null
            private set

        fun byte(): Int? {
            if (position >= data.size) {
                fail("Typed invocation payload is truncated.")
                return null
            }
            return data[position++].toInt() and 0xFF
        }

        fun u32(): Long? {
            if (data.size - position < 4) {
                fail("Typed invocation payload is truncated.")
                return null
            }
            var value = 0L
            repeat(4) { index -> value = value or ((data[position + index].toLong() and 0xFF) shl (index * 8)) }
            position += 4
            return value
        }

        fun u64(): ULong? {
            if (data.size - position < 8) {
                fail("Typed invocation payload is truncated.")
                return null
            }
            var value = 0UL
            repeat(8) { index -> value = value or ((data[position + index].toULong() and 0xFFUL) shl (index * 8)) }
            position += 8
            return value
        }

        fun string(): String? {
            val length = u32() ?: return null
            if (length > MAX_STRING_SIZE || length > remaining().toLong()) {
                fail("Typed invocation string is invalid.")
                return null
            }
            val end = position + length.toInt()
            val value = data.copyOfRange(position, end).decodeToString()
            position = end
            return value
        }

        fun rawBytes(): ByteArray? {
            val length = u32() ?: return null
            if (length > MAX_STRING_SIZE || length > remaining().toLong()) {
                fail("Typed invocation details are invalid.")
                return null
            }
            val end = position + length.toInt()
            val value = data.copyOfRange(position, end)
            position = end
            return value
        }

        fun count(): Long? {
            val value = u32() ?: return null
            if (value > MAX_COLLECTION_SIZE) {
                fail("Typed invocation collection is too large.")
                return null
            }
            return value
        }

        fun isAtEnd(): Boolean = position == data.size

        fun fail(message: String): Nothing? {
            if (error == null) error = message
            return null
        }

        fun failure(): WasmlineCallResult.Failure = malformed(error ?: "Typed invocation payload is invalid.")

        private fun remaining(): Int = data.size - position
    }

    private fun malformed(message: String): WasmlineCallResult.Failure =
        WasmlineCallResult.Failure(WasmlineCallError(WasmlineErrorCode.INVALID_PAYLOAD, message))
}
