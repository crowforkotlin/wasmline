@file:JsQualifier("WebAssembly")

package crow.wasmline.web

import org.khronos.webgl.ArrayBuffer

/**
 * Typed externals for the global `WebAssembly` namespace (js target only).
 *
 * Declared as real external classes so the js actuals stay free of `dynamic`
 * for module compilation, instantiation, and memory access.
 */

@JsName("Module")
internal external class NativeWasmModule(bytes: ArrayBuffer)

@JsName("Instance")
internal external class NativeWasmInstance(module: NativeWasmModule, imports: Any = definedExternally) {
    val exports: Any
}

@JsName("Memory")
internal external class NativeWasmMemory {
    val buffer: ArrayBuffer
}
