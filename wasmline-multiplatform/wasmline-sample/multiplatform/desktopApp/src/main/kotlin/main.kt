import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import crow.wasmline.sample.App
import crow.wasmline.sample.ir.EchoServiceImpl
import java.awt.Dimension

fun main() = application {
    Window(
        title = "Multiplatform App",
        state = rememberWindowState(width = 800.dp, height = 600.dp),
        onCloseRequest = ::exitApplication,
    ) {
        window.minimumSize = Dimension(350, 600)
        App(wasmPath = "test")
        EchoServiceImpl()
    }
}

