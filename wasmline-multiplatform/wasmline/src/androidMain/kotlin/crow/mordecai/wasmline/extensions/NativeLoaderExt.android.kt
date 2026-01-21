package crow.mordecai.wasmline.extensions

import crow.mordecai.wasmline.testAAA

actual fun loadNativeLibrary() {
    System.loadLibrary("wasmline")
}