package crow.mordecai.wasmline.extensions

import android.util.Log

private const val TAG = "KotlinWasm"

internal fun Any?.info(tag: String = TAG) { Log.i(tag, this.toString()) }