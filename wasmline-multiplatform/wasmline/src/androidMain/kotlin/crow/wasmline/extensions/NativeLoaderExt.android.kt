package crow.wasmline.extensions

actual fun loadNativeLibrary() {
    // Load wasmtime engine first (from engine module), then wasmline bridge (from core module).
    // The dynamic linker resolves wasmtime symbols from the already-loaded libwasmtime.so.
    System.loadLibrary("wasmtime")
    System.loadLibrary("wasmline")
}
