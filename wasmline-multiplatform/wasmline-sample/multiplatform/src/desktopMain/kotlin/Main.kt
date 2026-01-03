@file:OptIn(InternalResourceApi::class, ExperimentalSerializationApi::class)

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.useResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.application
import crow.mordecai.wasmline.Wasmline
import crow.mordecai.wasmline.WasmlineLoadState
import crow.mordecai.wasmline.extensions.info
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.getResourceUri
import org.jetbrains.compose.resources.imageResource
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
@Serializable
data class Data(
    val id: Long,
    val name: String,
    val key: String
)
private val baseJson = Json { prettyPrint = true; isLenient = true; }
@OptIn(ExperimentalResourceApi::class)
fun main() = application {
    Wasmline.init()
    var value by remember { mutableStateOf("Value") }
    val scope = rememberCoroutineScope()
    AppWindows {
        App(value) {
            scope.launch {
                println("launch")
                val data = ProtoBuf.encodeToByteArray(Data(1024, "desktop","key"))
                var startMs = System.currentTimeMillis()
                when(val loadState = Wasmline.load(getResourceUri("plugin.wasm").removePrefix("file:"), null, false)) {
                    is WasmlineLoadState.Failure -> { loadState.cause.info() }
                    is WasmlineLoadState.Success -> {
                        if (loadState.code == WasmlineLoadState.CODE_SUCCESS_JIT) {
                            "[Wasmline] Load jit success, spend ${System.currentTimeMillis() - startMs}  ms".info()
                        } else if (loadState.code == WasmlineLoadState.CODE_SUCCESS_AOT) {
                            "[Wasmline] Load aot success, spend ${System.currentTimeMillis() - startMs}  ms".info()
                        }
                        val module = loadState.wasmLine
                        startMs = System.currentTimeMillis()
                        module.setOutbound(dispatcher = { action, payload ->
                            "[Android] receive wasm action is : $action \t payload is $payload".info()
                            byteArrayOf()
                        })
//                        module.call("init", data)
                        val result = module.call("add", data)
                        val duration = System.currentTimeMillis() - startMs
                        value = "Result : \n\n${baseJson.encodeToString(ProtoBuf.decodeFromByteArray<Data>(result))}\n\ncall function duration : $duration ms"
                    }
                }
            }
        }
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