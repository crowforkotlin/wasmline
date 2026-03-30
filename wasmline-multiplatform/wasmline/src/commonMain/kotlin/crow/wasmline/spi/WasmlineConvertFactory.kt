package crow.wasmline.spi

/**
 * Phase-two extension point reserved for Wasmline value serialization.
 *
 * The current bridge pipeline still only supports phase-one payloads:
 * - `ByteArray`
 * - `Unit`
 *
 * This SPI is intentionally minimal for now. A later iteration can let the IR plugin
 * resolve argument/return codecs through one or more factories without changing the
 * single-bridge architecture introduced in phase one.
 */
interface WasmlineConvertFactory {
    fun codecOrNull(typeId: String): WasmlineValueCodec?
}

/** Codec abstraction reserved for future typed bridge marshalling. */
interface WasmlineValueCodec {
    val typeId: String

    fun encode(value: Any?): ByteArray

    fun decode(payload: ByteArray): Any?
}

/** Built-in bridge codec for phase-one `ByteArray` payloads. */
object WasmlineByteArrayCodec : WasmlineValueCodec {
    override val typeId: String = "kotlin.ByteArray"

    override fun encode(value: Any?): ByteArray {
        require(value is ByteArray) {
            "Expected ByteArray for codec '$typeId', but was ${value?.let { it::class.qualifiedName } ?: "null"}."
        }
        return value
    }

    override fun decode(payload: ByteArray): Any = payload
}

/** Built-in bridge codec for phase-one `Unit` values. */
object WasmlineUnitCodec : WasmlineValueCodec {
    override val typeId: String = "kotlin.Unit"

    override fun encode(value: Any?): ByteArray {
        require(value == null || value === Unit) {
            "Expected Unit for codec '$typeId', but was ${value?.let { it::class.qualifiedName } ?: "null"}."
        }
        return ByteArray(0)
    }

    override fun decode(payload: ByteArray): Any {
        require(payload.isEmpty()) {
            "Expected empty payload for codec '$typeId', but received ${payload.size} bytes."
        }
        return Unit
    }
}

/** Default built-in factory used as the future fallback for phase-one bridge payloads. */
object WasmlineBuiltinConvertFactory : WasmlineConvertFactory {
    override fun codecOrNull(typeId: String): WasmlineValueCodec? {
        return when (typeId) {
            WasmlineByteArrayCodec.typeId -> WasmlineByteArrayCodec
            WasmlineUnitCodec.typeId -> WasmlineUnitCodec
            else -> null
        }
    }
}


