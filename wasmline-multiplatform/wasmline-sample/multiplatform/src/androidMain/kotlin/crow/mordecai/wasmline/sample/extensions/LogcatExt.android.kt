package crow.mordecai.wasmline.sample.extensions

import android.util.Log

private const val TAG = "KotlinWasm"

actual fun Any?.info() { Log.i(TAG, this.toString()) }