@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package crow.wasmline.samples.minimal.service

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeViewport
import crow.wasmline.samples.minimal.library.platform.webEnvironment
import kotlinx.browser.window
import org.w3c.dom.url.URL

fun main() {
    ComposeViewport {
        val environment = remember {
            webEnvironment(URL("plugin/manifest.wlm", window.location.href).href)
        }
        App(environment)
    }
}
