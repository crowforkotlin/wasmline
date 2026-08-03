package crow.wasmline.web

/**
 * Platform bindings contract for the web targets (js and wasmJs).
 *
 * webMain only declares `expect` types and functions here. The concrete
 * bindings live in `WebBindings.js.kt` (typed externals) and
 * `WebBindings.wasmJs.kt` (JsAny + constant `js()` snippets), so no
 * platform-specific interop ever leaks into shared web code.
 *
 * 2026-07-29
 * @author crowforkotlin
 */

/** Opaque handle to an arbitrary JavaScript value. */
internal expect class WebJsValue

/** Opaque handle to a plain JavaScript object used as a property bag. */
internal expect class WebJsObject

/** Opaque handle to a JavaScript array of values. */
internal expect class WebJsArray

/** Opaque handle to a `Uint8Array` window over raw bytes or linear memory. */
internal expect class WebBytes

/** Opaque handle to a compiled `WebAssembly.Module`. */
internal expect class WebWasmModule

/** Opaque handle to an instantiated `WebAssembly.Instance`. */
internal expect class WebWasmInstance

internal expect fun webUndefined(): WebJsValue

/** True when [value] is Kotlin null, JS null, or JS undefined. */
internal expect fun webIsNullish(value: WebJsValue?): Boolean

/** JS-side `typeof` name, intended for diagnostics only. */
internal expect fun webTypeNameOf(value: WebJsValue): String

internal expect fun webNewObject(): WebJsObject

internal expect fun webObjectAsValue(obj: WebJsObject): WebJsValue

/** Reads a property; null when absent, null, or undefined. */
internal expect fun webObjectRead(obj: WebJsObject, name: String): WebJsValue?

/** Reads a property as a nested object; null when absent, null, or undefined. */
internal expect fun webObjectReadObject(obj: WebJsObject, name: String): WebJsObject?

internal expect fun webObjectWrite(obj: WebJsObject, name: String, value: WebJsValue)

internal expect fun webObjectWriteObject(obj: WebJsObject, name: String, value: WebJsObject)

internal expect fun webArrayOf(values: List<WebJsValue>): WebJsArray

internal expect fun webArrayAsValue(array: WebJsArray): WebJsValue

/** Reinterprets a value as an array without a runtime check. */
internal expect fun webValueToArray(value: WebJsValue): WebJsArray

internal expect fun webArraySize(array: WebJsArray): Int

internal expect fun webArrayAt(array: WebJsArray, index: Int): WebJsValue

internal expect fun webIsArray(value: WebJsValue): Boolean

internal expect fun webFromI32(value: Int): WebJsValue

internal expect fun webFromI64(value: Long): WebJsValue

internal expect fun webFromF32(value: Float): WebJsValue

internal expect fun webFromF64(value: Double): WebJsValue

internal expect fun webToI32(value: WebJsValue): Int

internal expect fun webToI64(value: WebJsValue): Long

internal expect fun webToF32(value: WebJsValue): Float

internal expect fun webToF64(value: WebJsValue): Double

/** Compiles a raw wasm binary through `new WebAssembly.Module(...)`. */
internal expect fun webCompileWasm(binary: ByteArray): WebWasmModule

/** Instantiates a compiled module with the supplied import object. */
internal expect fun webInstantiateWasm(module: WebWasmModule, imports: WebJsObject): WebWasmInstance

internal expect fun webExportsOf(instance: WebWasmInstance): WebJsObject

internal expect fun webIsFunction(value: WebJsValue): Boolean

internal expect fun webIsWasmMemory(value: WebJsValue): Boolean

/** Invokes a JS function; null when it returns nothing. */
internal expect fun webCallFunction(function: WebJsValue, args: WebJsArray): WebJsValue?

/**
 * Wraps a Kotlin handler into a variadic JS function so it can be installed
 * as a wasm import. A null result maps to JS `undefined`.
 */
internal expect fun webHostFunction(handler: (List<WebJsValue>) -> WebJsValue?): WebJsValue

/** Creates a `Uint8Array` window over `memory.buffer` at [pointer]/[length]. */
internal expect fun webMemoryBytes(memory: WebJsValue, pointer: Int, length: Int): WebBytes

/** Copies the window content out into a fresh Kotlin ByteArray. */
internal expect fun webBytesCopyOut(bytes: WebBytes): ByteArray

/** Copies Kotlin bytes into the window (window must be large enough). */
internal expect fun webBytesCopyIn(bytes: WebBytes, source: ByteArray)

/** Current epoch time in milliseconds (`Date.now()`). */
internal expect fun webNowMillis(): Double

/**
 * Downloads a binary resource with the Fetch API. Exactly one callback is
 * invoked: [onSuccess] with the body bytes on a 2xx response, otherwise
 * [onFailure] with a human-readable reason.
 */
internal expect fun webFetchBytes(url: String, onSuccess: (ByteArray) -> Unit, onFailure: (String) -> Unit)
