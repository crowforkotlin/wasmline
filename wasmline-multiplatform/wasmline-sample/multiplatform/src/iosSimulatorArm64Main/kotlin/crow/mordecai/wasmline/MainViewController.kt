package crow.mordecai.wasmline

import App
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.window.ComposeUIViewController
import crow.mordecai.wasmline.native.WasmlineBridge
import crow.mordecai.wasmline.native.testCLibrary

fun MainViewController() = ComposeUIViewController {
    MaterialTheme {
        App("") {
            WasmlineBridge.init()
            testCLibrary()
        }
    }
}