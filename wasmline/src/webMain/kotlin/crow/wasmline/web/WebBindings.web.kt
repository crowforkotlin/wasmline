package crow.wasmline.web

/**
 * Platform bindings contract for the web targets (js and wasmJs).
 *
 * webMain only declares `expect` types and functions here. The concrete
 * bindings live in `WebBindings.js.kt` (typed externals) and
 * `WebBindings.wasmJs.kt` (JsAny + constant `js()` snippets), so no
 * platform-specific interop ever leaks into shared web code.
 *
 * Opaque handle to an arbitrary JavaScript value.
 *
 * Date: 2026-07-29
 * Author: crowforkotlin
 */
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

/**
 * Describes one item returned by `WebAssembly.Module.exports()`.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 *
 * @property name Export field name.
 * @property kind JavaScript WebAssembly export kind.
 */
internal data class WebWasmModuleExport(val name: String, val kind: String)

/**
 * Describes one item returned by `WebAssembly.Module.imports()`.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 *
 * @property module Import module namespace.
 * @property name Import field name.
 * @property kind JavaScript WebAssembly import kind.
 */
internal data class WebWasmModuleImport(val module: String, val name: String, val kind: String)

/**
 * Captures a JavaScript function call without allowing a WebAssembly trap to
 * escape through the Kotlin/Wasm boundary.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
internal sealed interface WebWasmCallOutcome {
    /**
     * Represents a completed call and its optional JavaScript return value.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     *
     * @property value Optional JavaScript result.
     */
    data class Success(val value: WebJsValue?) : WebWasmCallOutcome

    /**
     * Represents a JavaScript exception or WebAssembly trap raised by a call.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     *
     * @property message Stable diagnostic text captured by the trampoline.
     */
    data class Failure(val message: String) : WebWasmCallOutcome
}

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

/** Returns the module's declared export inventory. */
internal expect fun webWasmModuleExports(module: WebWasmModule): List<WebWasmModuleExport>

/** Returns the module's declared import inventory. */
internal expect fun webWasmModuleImports(module: WebWasmModule): List<WebWasmModuleImport>

internal expect fun webExportsOf(instance: WebWasmInstance): WebJsObject

internal expect fun webIsFunction(value: WebJsValue): Boolean

internal expect fun webIsWasmMemory(value: WebJsValue): Boolean

/** Invokes a JS function; null when it returns nothing. */
internal expect fun webCallFunction(function: WebJsValue, args: WebJsArray): WebJsValue?

/** Invokes a JS function inside a platform-side `try/catch` trampoline. */
internal expect fun webCallFunctionSafely(function: WebJsValue, args: WebJsArray): WebWasmCallOutcome

/**
 * Wraps a Kotlin handler into a variadic JS function so it can be installed
 * as a wasm import. A null result maps to JS `undefined`.
 */
internal expect fun webHostFunction(handler: (List<WebJsValue>) -> WebJsValue?): WebJsValue

/** Creates a `Uint8Array` window over `memory.buffer` at [pointer]/[length]. */
internal expect fun webMemoryBytes(memory: WebJsValue, pointer: Int, length: Int): WebBytes

/** Returns the current linear-memory byte length. */
internal expect fun webMemoryByteSize(memory: WebJsValue): Long

/** Grows linear memory and returns its previous page count. */
internal expect fun webMemoryGrow(memory: WebJsValue, deltaPages: Int): Long

/** Copies the window content out into a fresh Kotlin ByteArray. */
internal expect fun webBytesCopyOut(bytes: WebBytes): ByteArray

/** Copies the window content into an existing Kotlin ByteArray. */
internal expect fun webBytesCopyOut(bytes: WebBytes, destination: ByteArray, destinationOffset: Int)

/** Copies a Kotlin ByteArray range into the window. */
internal expect fun webBytesCopyIn(bytes: WebBytes, source: ByteArray, sourceOffset: Int)

/** Current epoch time in milliseconds (`Date.now()`). */
internal expect fun webNowMillis(): Double

/**
 * Downloads a binary resource with the Fetch API. Exactly one callback is
 * invoked: [onSuccess] with the body bytes on a 2xx response, otherwise
 * [onFailure] with a human-readable reason.
 */
internal expect fun webFetchBytes(url: String, onSuccess: (ByteArray) -> Unit, onFailure: (String) -> Unit)
