@file:OptIn(ExperimentalComposeUiApi::class)

package crow.wasmline.sample

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport

private const val WEB_PLUGIN_URL = "plugin/wasmline-sample-sample-plugin.wasm"

fun main() {
    ComposeViewport {
        MaterialTheme {
            App(wasmPath = WEB_PLUGIN_URL)
        }
    }
}
