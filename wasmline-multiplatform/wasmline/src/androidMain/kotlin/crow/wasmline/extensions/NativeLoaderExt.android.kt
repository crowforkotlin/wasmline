package crow.wasmline.extensions

actual fun loadNativeLibrary() {
    System.loadLibrary("wasmline")
}
