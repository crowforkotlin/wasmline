package crow.wasmline.extensions

import android.util.Log

private const val TAG = "Wasmline-Logcat"

internal actual fun Any?.info() {
    Log.i(TAG, this.toString())
}