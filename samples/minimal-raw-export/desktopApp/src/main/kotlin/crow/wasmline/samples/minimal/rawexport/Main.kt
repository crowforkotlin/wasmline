package crow.wasmline.samples.minimal.rawexport

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import crow.wasmline.samples.minimal.library.platform.desktopEnvironment

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Minimal Raw Export",
        state = rememberWindowState(width = 480.dp, height = 360.dp),
    ) {
        val environment = remember { desktopEnvironment() }
        App(environment)
    }
}
