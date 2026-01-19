package crow.mordecai.wasmline.sample.extensions

actual fun Any?.info() { println(this.toString()) }