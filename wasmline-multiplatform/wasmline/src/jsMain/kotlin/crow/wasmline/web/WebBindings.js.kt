package crow.wasmline.web

import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.w3c.fetch.Response
import kotlin.js.Promise

/**
 * js target bindings for the web platform contract.
 *
 * Values are carried as `Any?` handles backed by typed externals plus a few
 * constant `js()` snippets, keeping `dynamic` out of the implementation.
 */

internal actual class WebJsValue internal constructor(internal val raw: Any?)

internal actual class WebJsObject internal constructor(internal val raw: Any)

internal actual class WebJsArray internal constructor(internal val raw: Array<Any?>)

internal actual class WebBytes internal constructor(internal val raw: Uint8Array)

internal actual class WebWasmModule internal constructor(internal val raw: NativeWasmModule)

internal actual class WebWasmInstance internal constructor(internal val raw: NativeWasmInstance)

internal actual fun webUndefined(): WebJsValue = WebJsValue(rawUndefined())

internal actual fun webIsNullish(value: WebJsValue?): Boolean = value == null || rawIsNullish(value.raw)

internal actual fun webTypeNameOf(value: WebJsValue): String = rawTypeOf(value.raw)

internal actual fun webNewObject(): WebJsObject = WebJsObject(rawNewObject())

internal actual fun webObjectAsValue(obj: WebJsObject): WebJsValue = WebJsValue(obj.raw)

internal actual fun webObjectRead(obj: WebJsObject, name: String): WebJsValue? {
    val value = rawReadProperty(obj.raw, name)
    return if (rawIsNullish(value)) null else WebJsValue(value)
}

internal actual fun webObjectReadObject(obj: WebJsObject, name: String): WebJsObject? {
    val value = rawReadProperty(obj.raw, name) ?: return null
    return if (rawIsNullish(value)) null else WebJsObject(value)
}

internal actual fun webObjectWrite(obj: WebJsObject, name: String, value: WebJsValue) {
    rawWriteProperty(obj.raw, name, value.raw)
}

internal actual fun webObjectWriteObject(obj: WebJsObject, name: String, value: WebJsObject) {
    rawWriteProperty(obj.raw, name, value.raw)
}

internal actual fun webArrayOf(values: List<WebJsValue>): WebJsArray = WebJsArray(values.map { value -> value.raw }.toTypedArray())

internal actual fun webArrayAsValue(array: WebJsArray): WebJsValue = WebJsValue(array.raw)

internal actual fun webValueToArray(value: WebJsValue): WebJsArray = WebJsArray(value.raw.unsafeCast<Array<Any?>>())

internal actual fun webArraySize(array: WebJsArray): Int = array.raw.size

internal actual fun webArrayAt(array: WebJsArray, index: Int): WebJsValue = WebJsValue(array.raw[index])

internal actual fun webIsArray(value: WebJsValue): Boolean = rawIsArray(value.raw)

internal actual fun webFromI32(value: Int): WebJsValue = WebJsValue(value)

internal actual fun webFromI64(value: Long): WebJsValue = WebJsValue(rawBigIntOf(value.toString()))

internal actual fun webFromF32(value: Float): WebJsValue = WebJsValue(value)

internal actual fun webFromF64(value: Double): WebJsValue = WebJsValue(value)

internal actual fun webToI32(value: WebJsValue): Int = value.raw.unsafeCast<Int>()

internal actual fun webToI64(value: WebJsValue): Long = value.raw.toString().toLong()

internal actual fun webToF32(value: WebJsValue): Float = value.raw.unsafeCast<Double>().toFloat()

internal actual fun webToF64(value: WebJsValue): Double = value.raw.unsafeCast<Double>()

internal actual fun webCompileWasm(binary: ByteArray): WebWasmModule {
    val bytes = binary.unsafeCast<Int8Array>()
    return WebWasmModule(NativeWasmModule(bytes.buffer))
}

internal actual fun webInstantiateWasm(module: WebWasmModule, imports: WebJsObject): WebWasmInstance =
    WebWasmInstance(NativeWasmInstance(module.raw, imports.raw))

internal actual fun webWasmModuleExports(module: WebWasmModule): List<WebWasmModuleExport> =
    rawModuleExports(module.raw).map { descriptor ->
        WebWasmModuleExport(
            name = rawReadProperty(descriptor, "name").toString(),
            kind = rawReadProperty(descriptor, "kind").toString(),
        )
    }

internal actual fun webWasmModuleImports(module: WebWasmModule): List<WebWasmModuleImport> =
    rawModuleImports(module.raw).map { descriptor ->
        WebWasmModuleImport(
            module = rawReadProperty(descriptor, "module").toString(),
            name = rawReadProperty(descriptor, "name").toString(),
            kind = rawReadProperty(descriptor, "kind").toString(),
        )
    }

internal actual fun webExportsOf(instance: WebWasmInstance): WebJsObject = WebJsObject(instance.raw.exports)

internal actual fun webIsFunction(value: WebJsValue): Boolean = rawIsFunction(value.raw)

internal actual fun webIsWasmMemory(value: WebJsValue): Boolean = rawIsWasmMemory(value.raw)

internal actual fun webCallFunction(function: WebJsValue, args: WebJsArray): WebJsValue? {
    val callee = checkNotNull(function.raw) { "Cannot invoke a null JS function reference." }
    val result = rawApplyFunction(callee, args.raw)
    return if (rawIsNullish(result)) null else WebJsValue(result)
}

internal actual fun webCallFunctionSafely(function: WebJsValue, args: WebJsArray): WebWasmCallOutcome {
    val callee = checkNotNull(function.raw) { "Cannot invoke a null JS function reference." }
    val outcome = rawTryApplyFunction(callee, args.raw)
    return if (rawOutcomeSucceeded(outcome)) {
        val value = rawReadProperty(outcome, "value")
        WebWasmCallOutcome.Success(if (rawIsNullish(value)) null else WebJsValue(value))
    } else {
        WebWasmCallOutcome.Failure(rawReadProperty(outcome, "message").toString())
    }
}

internal actual fun webHostFunction(handler: (List<WebJsValue>) -> WebJsValue?): WebJsValue = WebJsValue(
    rawVariadicFunction { rawArgs ->
        handler(rawArgs.map(::WebJsValue))?.raw
    },
)

internal actual fun webMemoryBytes(memory: WebJsValue, pointer: Int, length: Int): WebBytes {
    val raw = memory.raw.unsafeCast<NativeWasmMemory>()
    return WebBytes(Uint8Array(raw.buffer, pointer, length))
}

internal actual fun webMemoryByteSize(memory: WebJsValue): Long = memory.raw.unsafeCast<NativeWasmMemory>().buffer.byteLength.toLong()

internal actual fun webMemoryGrow(memory: WebJsValue, deltaPages: Int): Long =
    memory.raw.unsafeCast<NativeWasmMemory>().grow(deltaPages).toLong()

internal actual fun webBytesCopyOut(bytes: WebBytes): ByteArray {
    val copy = Int8Array(bytes.raw.length)
    copy.set(Int8Array(bytes.raw.buffer, bytes.raw.byteOffset, bytes.raw.length))
    return copy.unsafeCast<ByteArray>()
}

internal actual fun webBytesCopyOut(bytes: WebBytes, destination: ByteArray, destinationOffset: Int) {
    val destinationView = destination.unsafeCast<Int8Array>()
    destinationView.set(Int8Array(bytes.raw.buffer, bytes.raw.byteOffset, bytes.raw.length), destinationOffset)
}

internal actual fun webBytesCopyIn(bytes: WebBytes, source: ByteArray, sourceOffset: Int) {
    val sourceView = source.unsafeCast<Int8Array>()
    bytes.raw.set(Uint8Array(sourceView.buffer, sourceView.byteOffset + sourceOffset, bytes.raw.length))
}

internal actual fun webNowMillis(): Double = rawNowMillis()

internal actual fun webFetchBytes(url: String, onSuccess: (ByteArray) -> Unit, onFailure: (String) -> Unit) {
    fetch(url)
        .then { response ->
            check(response.ok) { "HTTP ${response.status} ${response.statusText}" }
            response.arrayBuffer()
        }
        .unsafeCast<Promise<ArrayBuffer>>()
        .then { buffer -> onSuccess(Int8Array(buffer).unsafeCast<ByteArray>()) }
        .catch { failure -> onFailure(failure.message ?: failure.toString()) }
}

private external fun fetch(resource: String): Promise<Response>

@JsName("BigInt")
private external fun rawBigIntOf(value: String): Any

private fun rawUndefined(): Any? = js("undefined")

private fun rawIsNullish(value: Any?): Boolean = js("value == null")

private fun rawTypeOf(value: Any?): String = js("typeof value")

private fun rawNewObject(): Any = js("({})")

private fun rawReadProperty(target: Any, name: String): Any? = js("target[name]")

private fun rawWriteProperty(target: Any, name: String, value: Any?): Unit = js("{ target[name] = value; }")

private fun rawIsArray(value: Any?): Boolean = js("Array.isArray(value)")

private fun rawIsFunction(value: Any?): Boolean = js("typeof value === 'function'")

private fun rawIsWasmMemory(value: Any?): Boolean = js("value instanceof WebAssembly.Memory")

private fun rawApplyFunction(fn: Any, args: Array<Any?>): Any? = js("fn.apply(undefined, args)")

private fun rawTryApplyFunction(fn: Any, args: Array<Any?>): Any = js(
    """(() => {
        try {
            return { ok: true, value: fn.apply(undefined, args) };
        } catch (error) {
            return { ok: false, message: error && error.message ? error.message : String(error) };
        }
    })()""",
)

private fun rawOutcomeSucceeded(outcome: Any): Boolean = js("outcome.ok === true")

private fun rawNowMillis(): Double = js("Date.now()")

private fun rawVariadicFunction(handler: (Array<Any?>) -> Any?): Any =
    js("(function () { return handler(Array.prototype.slice.call(arguments)); })")

private fun rawModuleExports(module: NativeWasmModule): Array<Any> = js("WebAssembly.Module.exports(module)")

private fun rawModuleImports(module: NativeWasmModule): Array<Any> = js("WebAssembly.Module.imports(module)")
