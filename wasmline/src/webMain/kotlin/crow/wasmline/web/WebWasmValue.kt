package crow.wasmline.web

/**
 * Type-safe model of the wasm core values exchanged with a plugin module.
 *
 * The four numeric types are boxed once here so that later bridge code only
 * deals with [WebWasmValue]; [WebWasmValueCodec] performs the single
 * boundary conversion to and from JS value handles.
 *
 * Date: 2026-07-29
 * Author: crowforkotlin
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
 * Category of a caller-declared result ABI disagreement.
 *
 * Mirrors the Core result validation categories so the Web backend can report
 * the same stable codes as native backends.
 */
internal enum class WebWasmResultMismatch {
    /** Produced and declared result counts disagree, or no value was produced. */
    COUNT,

    /** A produced value could not be converted to its declared result type. */
    TYPE,
}

/**
 * Converts between [WebWasmValue] and JS value handles.
 *
 * Decoding always requires an explicit [WebWasmType]: JS numbers do not
 * carry the wasm type, so guessing from the runtime representation is
 * deliberately not supported.
 *
 * Date: 2026-07-29
 * Author: crowforkotlin
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
     *
     * A shape or conversion mismatch raises [WebWasmResultAbiException] so
     * callers can distinguish a completed call with a mis-declared result ABI
     * from an actual wasm trap. The browser API carries no runtime wasm type
     * for plain numbers, so a wrong declaration between two JS number types
     * (`i32`, `f32`, `f64`) cannot be detected. `i64` is distinguishable
     * because it crosses the JavaScript boundary as `bigint`.
     */
    fun decodeResults(result: WebJsValue?, types: List<WebWasmType>): List<WebWasmValue> {
        // A declared-void result is intentionally not cross-checked: this shared
        // decoder also serves the Service path (WebWasmPlugin), whose lifecycle
        // exports are called without declared result types, and the browser API
        // gives no way to tell a discarded value from an absent one.
        if (types.isEmpty()) return emptyList()

        val value = result ?: throw WebWasmResultAbiException(
            WebWasmResultMismatch.COUNT,
            "Wasm call returned no value but ${types.size} result(s) were expected.",
        )

        if (types.size == 1) {
            // WebAssembly scalar results are never JS arrays; an array here means
            // the export produced several values while one was declared.
            if (webIsArray(value)) {
                throw WebWasmResultAbiException(
                    WebWasmResultMismatch.COUNT,
                    "Wasm call returned ${webArraySize(webValueToArray(value))} result(s) but 1 was expected.",
                )
            }
            return listOf(decodeResult(value, types.first()))
        }

        if (!webIsArray(value)) {
            throw WebWasmResultAbiException(
                WebWasmResultMismatch.COUNT,
                "Wasm call returned a single '${webTypeNameOf(value)}' but ${types.size} results were expected.",
            )
        }

        val array = webValueToArray(value)
        val count = webArraySize(array)
        if (count != types.size) {
            throw WebWasmResultAbiException(
                WebWasmResultMismatch.COUNT,
                "Wasm call returned $count result(s) but ${types.size} were expected.",
            )
        }
        return List(count) { index -> decodeResult(webArrayAt(array, index), types[index]) }
    }

    private fun decodeResult(value: WebJsValue, type: WebWasmType): WebWasmValue {
        // The browser API exposes no wasm type for plain numbers, but the JS
        // representation still separates `bigint` (`i64`) from `number`.
        val actual = webTypeNameOf(value)
        val expected = when (type) {
            WebWasmType.I64 -> BIGINT_TYPE
            WebWasmType.I32, WebWasmType.F32, WebWasmType.F64 -> NUMBER_TYPE
        }
        if (actual != expected) {
            throw WebWasmResultAbiException(
                WebWasmResultMismatch.TYPE,
                "Wasm call result has JavaScript type '$actual' but $type expects '$expected'.",
            )
        }
        return try {
            decode(value, type)
        } catch (failure: Throwable) {
            throw WebWasmResultAbiException(
                WebWasmResultMismatch.TYPE,
                "Wasm call result could not be decoded as $type: ${failure.message ?: failure}",
            )
        }
    }

    private const val NUMBER_TYPE = "number"
    private const val BIGINT_TYPE = "bigint"
}
