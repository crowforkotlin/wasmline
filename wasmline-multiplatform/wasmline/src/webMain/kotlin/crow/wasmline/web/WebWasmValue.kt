package crow.wasmline.web

/**
 * Type-safe model of the wasm core values exchanged with a plugin module.
 *
 * The four numeric types are boxed once here so that later bridge code only
 * deals with [WebWasmValue]; [WebWasmValueCodec] performs the single
 * boundary conversion to and from JS value handles.
 *
 * 2026-07-29
 * @author crowforkotlin
 */
internal sealed interface WebWasmValue {
    val type: WebWasmType

    data class I32(val value: Int) : WebWasmValue {
        override val type: WebWasmType get() = WebWasmType.I32
    }

    data class I64(val value: Long) : WebWasmValue {
        override val type: WebWasmType get() = WebWasmType.I64
    }

    data class F32(val value: Float) : WebWasmValue {
        override val type: WebWasmType get() = WebWasmType.F32
    }

    data class F64(val value: Double) : WebWasmValue {
        override val type: WebWasmType get() = WebWasmType.F64
    }
}

/** Wasm core numeric types supported by the web bridge. */
internal enum class WebWasmType {
    I32,
    I64,
    F32,
    F64,
}

/**
 * Converts between [WebWasmValue] and JS value handles.
 *
 * Decoding always requires an explicit [WebWasmType]: JS numbers do not
 * carry the wasm type, so guessing from the runtime representation is
 * deliberately not supported.
 *
 * 2026-07-29
 * @author crowforkotlin
 */
internal object WebWasmValueCodec {

    fun encode(value: WebWasmValue): WebJsValue = when (value) {
        is WebWasmValue.I32 -> webFromI32(value.value)
        is WebWasmValue.I64 -> webFromI64(value.value)
        is WebWasmValue.F32 -> webFromF32(value.value)
        is WebWasmValue.F64 -> webFromF64(value.value)
    }

    fun decode(value: WebJsValue, type: WebWasmType): WebWasmValue = when (type) {
        WebWasmType.I32 -> WebWasmValue.I32(webToI32(value))
        WebWasmType.I64 -> WebWasmValue.I64(webToI64(value))
        WebWasmType.F32 -> WebWasmValue.F32(webToF32(value))
        WebWasmType.F64 -> WebWasmValue.F64(webToF64(value))
    }

    /**
     * Maps a raw invocation result onto the declared result types.
     *
     * WebAssembly returns `undefined` for zero results, a plain value for a
     * single result, and a JS array for multi-value results; all three
     * shapes are normalized into a typed list here.
     */
    fun decodeResults(result: WebJsValue?, types: List<WebWasmType>): List<WebWasmValue> {
        if (types.isEmpty()) return emptyList()

        val value = result ?: throw WebWasmException(
            "Wasm call returned no value but ${types.size} result(s) were expected.",
        )

        if (types.size == 1) return listOf(decode(value, types.first()))

        if (!webIsArray(value)) {
            throw WebWasmException(
                "Wasm call returned a single '${webTypeNameOf(value)}' but ${types.size} results were expected.",
            )
        }

        val array = webValueToArray(value)
        val count = webArraySize(array)
        if (count != types.size) {
            throw WebWasmException("Wasm call returned $count result(s) but ${types.size} were expected.")
        }
        return List(count) { index -> decode(webArrayAt(array, index), types[index]) }
    }
}
