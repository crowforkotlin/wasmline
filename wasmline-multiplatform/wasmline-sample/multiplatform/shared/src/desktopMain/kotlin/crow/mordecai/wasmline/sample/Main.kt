@file:OptIn(InternalResourceApi::class, ExperimentalSerializationApi::class)

package crow.wasmline.sample

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.application
import crow.wasmline.Wasmline
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.readResourceBytes
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.intui.window.styling.light
import org.jetbrains.jewel.intui.window.styling.lightWithLightHeader
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.DecoratedWindowScope
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.styling.TitleBarColors
import org.jetbrains.jewel.window.styling.TitleBarStyle
import java.awt.Dimension
import java.io.File

private val baseJson = Json { prettyPrint = true; isLenient = true; }
@OptIn(ExperimentalResourceApi::class)
fun main() = application {
    Wasmline.init()
    val wasmFile = extractResourceToTemp("plugin.pwasm")
    println("Wasm extracted to: ${wasmFile.absolutePath}")
    AppWindows {
        App(
            wasmPath = wasmFile.absolutePath,
        )
    }
}

@Composable
fun ApplicationScope.AppWindows(content: @Composable () -> Unit) {
    var iconPainter by remember { mutableStateOf<BitmapPainter?>(null) }
    LaunchedEffect(Unit) {
        iconPainter = BitmapPainter(image = readResourceBytes("favicon.ico").decodeToImageBitmap())
    }
    IntUiTheme(
        theme = JewelTheme.lightThemeDefinition(),
        styling = ComponentStyling.default()
            .decoratedWindow(
                titleBarStyle = TitleBarStyle.light(
                    colors = TitleBarColors.lightWithLightHeader(backgroundColor = Color.White.copy(alpha = 0.7f))
                )
            ),
    ) {
        MaterialTheme {
            DecoratedWindow(
                onCloseRequest = ::exitApplication,
                title = "Wasmline Sample ",
                enabled = true,
                visible = true,
                icon = iconPainter
            ) {
                window.minimumSize = Dimension(352, 320)
                DesktopTitleBar()
                content()
            }
        }
    }
}

@Composable
fun DecoratedWindowScope.DesktopTitleBar(
    title: String = "Wasmline"
) {
    TitleBar {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}


/**
 * 将 Compose 资源或 ClassLoader 资源提取到临时文件
 */
@OptIn(ExperimentalResourceApi::class)
fun extractResourceToTemp(resourcePath: String): File {
    val tempFile = File.createTempFile("wasmline_plugin", ".pwasm")
    tempFile.deleteOnExit() // 程序退出时自动删除
    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(resourcePath)
        ?: error("Resource not found: $resourcePath")
    stream.use { input ->
        tempFile.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    return tempFile
}