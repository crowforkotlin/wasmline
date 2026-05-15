package crow.wasmline.sample.extensions

actual fun Any?.info() { println(this.toString()) }