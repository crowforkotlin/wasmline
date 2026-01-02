package crow.mordecai.wasmline.common.extensions

actual fun loadNativeLibrary() {
    System.loadLibrary("wasmline")
}