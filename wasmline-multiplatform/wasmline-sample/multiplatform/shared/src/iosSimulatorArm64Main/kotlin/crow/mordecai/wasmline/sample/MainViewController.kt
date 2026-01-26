package crow.mordecai.wasmline.sample

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSBundle

fun MainViewController() = ComposeUIViewController {
    MaterialTheme {
        App(wasmPath = NSBundle.mainBundle.pathForResource("plugin", "cwasm") ?: return@MaterialTheme)
    }
}