package crow.wasmline.extensions

internal actual fun ensureNativeRuntimeLoaded() {
    System.loadLibrary("wasmline")
}
