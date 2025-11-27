package crow.wasmtime.wasmline

import android.util.Log

const val TAG = "KotlinWasm"

fun Any?.info(tag: String = "TAG") { Log.i(tag, this.toString()) }