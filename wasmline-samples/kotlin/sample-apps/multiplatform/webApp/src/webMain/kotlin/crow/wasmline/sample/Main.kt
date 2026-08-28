@file:OptIn(ExperimentalComposeUiApi::class)

package crow.wasmline.sample

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

private const val WEB_PLUGIN_URL = "plugin/manifest.wlm"

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
        val timezone = TimeZone.of(zoneId = "Asia/Shanghai")
        val now = Clock.System.now()
        val datetime = now.toLocalDateTime(timezone)
        return "$wasmPath${separator}_fresh=${datetime.toString().toLong()}"
    }
}
