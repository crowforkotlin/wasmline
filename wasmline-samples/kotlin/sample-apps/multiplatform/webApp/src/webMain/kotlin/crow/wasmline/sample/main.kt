@file:OptIn(ExperimentalComposeUiApi::class)

package crow.wasmline.sample

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

private const val WEB_PLUGIN_URL = "plugin/wasmline-sample-sample-plugin.wasm"

fun main() {
    ComposeViewport(document.body!!) {
        MaterialTheme {
            App(wasmPath = WEB_PLUGIN_URL)
        }
    }
}
