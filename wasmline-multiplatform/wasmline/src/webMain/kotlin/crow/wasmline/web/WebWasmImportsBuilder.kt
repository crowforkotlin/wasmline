package crow.wasmline.web

/**
 * Builder for the nested `WebAssembly.Instance` import object.
 *
 * Import entries are grouped by module namespace (for example `env` or
 * `wasi_snapshot_preview1`). Host callbacks are plain Kotlin lambdas; the
 * JS glue is produced once by the bindings layer.
 *
 * 2026-07-29
 * @author crowforkotlin
 */
internal class WebWasmImportsBuilder {

    private val root = webNewObject()

    /**
     * Registers a typed host function import: incoming arguments are decoded
     * against [paramTypes] and handler results are validated against
     * [resultTypes] before being handed back to the wasm caller.
     */
    fun function(
        module: String,
        name: String,
        paramTypes: List<WebWasmType>,
        resultTypes: List<WebWasmType>,
        handler: (List<WebWasmValue>) -> List<WebWasmValue>,
    ): WebWasmImportsBuilder = rawFunction(module, name) { rawArgs ->
        if (rawArgs.size < paramTypes.size) {
            throw WebWasmException(
                "Host import '$module.$name' expected ${paramTypes.size} argument(s) but received ${rawArgs.size}.",
            )
        }
        val params = paramTypes.mapIndexed { index, type -> WebWasmValueCodec.decode(rawArgs[index], type) }
        val results = handler(params)
        if (results.size != resultTypes.size) {
            throw WebWasmException(
                "Host import '$module.$name' produced ${results.size} result(s) but declared ${resultTypes.size}.",
            )
        }
        when (results.size) {
            0 -> null
            1 -> WebWasmValueCodec.encode(results.first())
            else -> webArrayAsValue(webArrayOf(results.map(WebWasmValueCodec::encode)))
        }
    }

    /**
     * Registers a raw host function import for low-level shims (for example
     * WASI stubs) that work with JS value handles directly.
     */
    fun rawFunction(module: String, name: String, handler: (List<WebJsValue>) -> WebJsValue?): WebWasmImportsBuilder = apply {
        webObjectWrite(namespace(module), name, webHostFunction(handler))
    }

    /** Registers an arbitrary pre-built import value (memory, global, ...). */
    fun value(module: String, name: String, value: WebJsValue): WebWasmImportsBuilder = apply {
        webObjectWrite(namespace(module), name, value)
    }

    fun build(): WebJsObject = root

    private fun namespace(module: String): WebJsObject = webObjectReadObject(root, module) ?: webNewObject().also { created ->
        webObjectWriteObject(root, module, created)
    }
}
