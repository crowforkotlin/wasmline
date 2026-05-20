package crow.wasmline.extensions

internal actual fun Any?.info() {
    browserInfo(this)
}
