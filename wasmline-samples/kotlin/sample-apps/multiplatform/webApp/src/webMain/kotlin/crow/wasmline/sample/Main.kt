@file:OptIn(ExperimentalComposeUiApi::class)

package crow.wasmline.sample

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlin.js.Date

private const val WEB_PLUGIN_URL = "plugin/wasmline-sample-sample-plugin.wasm"

fun main() {
    ComposeViewport {
        MaterialTheme {
            App(
                wasmPath = WEB_PLUGIN_URL,
                assetRefresher = WebAssetRefresher,
            )
        }
    }
}

/**
 * Web implementation of [AssetRefresher].
 * Appends a cache-bust query parameter to bypass browser HTTP caching.
 */
private object WebAssetRefresher : AssetRefresher {
    override suspend fun refresh(wasmPath: String): String {
        val separator = if ('?' in wasmPath) '&' else '?'
        return "$wasmPath${separator}_fresh=${Date.now().toLong()}"
    }
}
