@file:OptIn(ExperimentalWasmJsInterop::class)

package crow.wasmline.web

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js

/**
 * wasmJs target bindings for the web platform contract.
 *
 * Values are carried as `JsAny` handles; every JavaScript touchpoint is a
 * constant single-expression `js()` snippet, as required by the strict
 * Kotlin/Wasm interop rules.
 */

internal actual class WebJsValue internal constructor(internal val raw: JsAny?)

internal actual class WebJsObject internal constructor(internal val raw: JsAny)

internal actual class WebJsArray internal constructor(internal val raw: JsAny)

internal actual class WebBytes internal constructor(internal val raw: JsAny)

internal actual class WebWasmModule internal constructor(internal val raw: JsAny)

internal actual class WebWasmInstance internal constructor(internal val raw: JsAny)

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

internal actual fun webArrayOf(values: List<WebJsValue>): WebJsArray {
    val array = rawNewArray()
    for (value in values) {
        rawArrayPush(array, value.raw)
    }
    return WebJsArray(array)
}

internal actual fun webArrayAsValue(array: WebJsArray): WebJsValue = WebJsValue(array.raw)

internal actual fun webValueToArray(value: WebJsValue): WebJsArray =
    WebJsArray(checkNotNull(value.raw) { "Cannot treat a null JS value as an array." })

internal actual fun webArraySize(array: WebJsArray): Int = rawArrayLength(array.raw)

internal actual fun webArrayAt(array: WebJsArray, index: Int): WebJsValue = WebJsValue(rawArrayAt(array.raw, index))

internal actual fun webIsArray(value: WebJsValue): Boolean = rawIsArray(value.raw)

internal actual fun webFromI32(value: Int): WebJsValue = WebJsValue(rawFromI32(value))

internal actual fun webFromI64(value: Long): WebJsValue = WebJsValue(rawFromI64(value))

internal actual fun webFromF32(value: Float): WebJsValue = WebJsValue(rawFromF32(value))

internal actual fun webFromF64(value: Double): WebJsValue = WebJsValue(rawFromF64(value))

internal actual fun webToI32(value: WebJsValue): Int = rawToI32(value.raw)

internal actual fun webToI64(value: WebJsValue): Long = rawToI64(value.raw)

internal actual fun webToF32(value: WebJsValue): Float = rawToF32(value.raw)

internal actual fun webToF64(value: WebJsValue): Double = rawToF64(value.raw)

internal actual fun webCompileWasm(binary: ByteArray): WebWasmModule {
    val bytes = rawNewUint8Array(binary.size)
    for (index in binary.indices) {
        rawWriteByte(bytes, index, binary[index].toInt() and 0xFF)
    }
    return WebWasmModule(rawNewWasmModule(bytes))
}

internal actual fun webInstantiateWasm(module: WebWasmModule, imports: WebJsObject): WebWasmInstance =
    WebWasmInstance(rawNewWasmInstance(module.raw, imports.raw))

internal actual fun webWasmModuleExports(module: WebWasmModule): List<WebWasmModuleExport> {
    val descriptors = rawWasmModuleExports(module.raw)
    return List(rawArrayLength(descriptors)) { index ->
        val descriptor = checkNotNull(rawArrayAt(descriptors, index))
        WebWasmModuleExport(
            name = rawStringProperty(descriptor, "name"),
            kind = rawStringProperty(descriptor, "kind"),
        )
    }
}

internal actual fun webWasmModuleImports(module: WebWasmModule): List<WebWasmModuleImport> {
    val descriptors = rawWasmModuleImports(module.raw)
    return List(rawArrayLength(descriptors)) { index ->
        val descriptor = checkNotNull(rawArrayAt(descriptors, index))
        WebWasmModuleImport(
            module = rawStringProperty(descriptor, "module"),
            name = rawStringProperty(descriptor, "name"),
            kind = rawStringProperty(descriptor, "kind"),
        )
    }
}

internal actual fun webExportsOf(instance: WebWasmInstance): WebJsObject = WebJsObject(rawWasmExports(instance.raw))

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
        val count = rawArrayLength(rawArgs)
        val args = List(count) { index -> WebJsValue(rawArrayAt(rawArgs, index)) }
        handler(args)?.raw
    },
)

internal actual fun webMemoryBytes(memory: WebJsValue, pointer: Int, length: Int): WebBytes {
    val raw = checkNotNull(memory.raw) { "Cannot view a null WebAssembly.Memory reference." }
    return WebBytes(rawMemoryBytes(raw, pointer, length))
}

internal actual fun webMemoryByteSize(memory: WebJsValue): Long {
    val raw = checkNotNull(memory.raw) { "Cannot inspect a null WebAssembly.Memory reference." }
    return rawMemoryByteSize(raw).toLong()
}

internal actual fun webMemoryGrow(memory: WebJsValue, deltaPages: Int): Long {
    val raw = checkNotNull(memory.raw) { "Cannot grow a null WebAssembly.Memory reference." }
    return rawMemoryGrow(raw, deltaPages).toLong()
}

internal actual fun webBytesCopyOut(bytes: WebBytes): ByteArray {
    val length = rawByteLength(bytes.raw)
    return ByteArray(length) { index -> rawReadByte(bytes.raw, index).toByte() }
}

internal actual fun webBytesCopyIn(bytes: WebBytes, source: ByteArray) {
    for (index in source.indices) {
        rawWriteByte(bytes.raw, index, source[index].toInt() and 0xFF)
    }
}

internal actual fun webNowMillis(): Double = rawNowMillis()

internal actual fun webFetchBytes(url: String, onSuccess: (ByteArray) -> Unit, onFailure: (String) -> Unit) {
    rawFetchBytes(
        url = url,
        onSuccess = { bytes -> onSuccess(webBytesCopyOut(WebBytes(bytes))) },
        onFailure = onFailure,
    )
}

private fun rawUndefined(): JsAny? = js("undefined")

private fun rawIsNullish(value: JsAny?): Boolean = js("value == null")

private fun rawTypeOf(value: JsAny?): String = js("typeof value")

private fun rawNewObject(): JsAny = js("({})")

private fun rawReadProperty(target: JsAny, name: String): JsAny? = js("target[name]")

private fun rawWriteProperty(target: JsAny, name: String, value: JsAny?): Unit = js("{ target[name] = value; }")

private fun rawNewArray(): JsAny = js("[]")

private fun rawArrayPush(array: JsAny, value: JsAny?): Unit = js("{ array.push(value); }")

private fun rawArrayLength(array: JsAny): Int = js("array.length")

private fun rawArrayAt(array: JsAny, index: Int): JsAny? = js("array[index]")

private fun rawIsArray(value: JsAny?): Boolean = js("Array.isArray(value)")

private fun rawFromI32(value: Int): JsAny = js("value")

private fun rawFromI64(value: Long): JsAny = js("BigInt.asIntN(64, value)")

private fun rawFromF32(value: Float): JsAny = js("value")

private fun rawFromF64(value: Double): JsAny = js("value")

private fun rawToI32(value: JsAny?): Int = js("value")

private fun rawToI64(value: JsAny?): Long = js("BigInt.asIntN(64, value)")

private fun rawToF32(value: JsAny?): Float = js("value")

private fun rawToF64(value: JsAny?): Double = js("value")

private fun rawNewUint8Array(size: Int): JsAny = js("new Uint8Array(size)")

private fun rawReadByte(array: JsAny, index: Int): Int = js("array[index]")

private fun rawWriteByte(array: JsAny, index: Int, value: Int): Unit = js("{ array[index] = value; }")

private fun rawByteLength(array: JsAny): Int = js("array.length")

private fun rawNewWasmModule(bytes: JsAny): JsAny = js("new WebAssembly.Module(bytes)")

private fun rawNewWasmInstance(module: JsAny, imports: JsAny): JsAny = js("new WebAssembly.Instance(module, imports)")

private fun rawWasmExports(instance: JsAny): JsAny = js("instance.exports")

private fun rawWasmModuleExports(module: JsAny): JsAny = js("WebAssembly.Module.exports(module)")

private fun rawWasmModuleImports(module: JsAny): JsAny = js("WebAssembly.Module.imports(module)")

private fun rawStringProperty(target: JsAny, name: String): String = js("String(target[name])")

private fun rawIsFunction(value: JsAny?): Boolean = js("typeof value === 'function'")

private fun rawIsWasmMemory(value: JsAny?): Boolean = js("value instanceof WebAssembly.Memory")

private fun rawApplyFunction(fn: JsAny, args: JsAny): JsAny? = js("fn.apply(undefined, args)")

private fun rawTryApplyFunction(fn: JsAny, args: JsAny): JsAny = js(
    """(() => {
        try {
            return { ok: true, value: fn.apply(undefined, args) };
        } catch (error) {
            return { ok: false, message: error && error.message ? error.message : String(error) };
        }
    })()""",
)

private fun rawOutcomeSucceeded(outcome: JsAny): Boolean = js("outcome.ok === true")

private fun rawMemoryBytes(memory: JsAny, pointer: Int, length: Int): JsAny = js("new Uint8Array(memory.buffer, pointer, length)")

private fun rawMemoryByteSize(memory: JsAny): Double = js("memory.buffer.byteLength")

private fun rawMemoryGrow(memory: JsAny, deltaPages: Int): Double = js("memory.grow(deltaPages)")

private fun rawNowMillis(): Double = js("Date.now()")

private fun rawVariadicFunction(handler: (JsAny) -> JsAny?): JsAny = js("((...args) => handler(args))")

private fun rawFetchBytes(url: String, onSuccess: (JsAny) -> Unit, onFailure: (String) -> Unit): Unit = js(
    """{
        fetch(url)
            .then((response) => {
                if (!response.ok) {
                    throw new Error('HTTP ' + response.status + ' ' + response.statusText);
                }
                return response.arrayBuffer();
            })
            .then((buffer) => { onSuccess(new Uint8Array(buffer)); })
            .catch((error) => { onFailure(error && error.message ? error.message : String(error)); });
    }""",
)
