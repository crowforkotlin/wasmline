package crow.wasmline.samples.minimal.service

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import crow.wasmline.link
import crow.wasmline.samples.minimal.library.model.MinimalExample
import crow.wasmline.samples.minimal.library.runtime.PluginEnvironment
import crow.wasmline.samples.minimal.library.ui.MinimalApp
import crow.wasmline.samples.minimal.service.common.GreetingService

@Composable
fun App(environment: PluginEnvironment) {
    val example = remember {
        MinimalExample(
            initialText = "GreetingService",
            accent = Color(0xFF087F70),
            invocation = { module ->
                println("[host] GreetingService.greet(Wasmline)")
                module.link<GreetingService>().greet("Wasmline")
            },
        )
    }
    MinimalApp(example, environment)
}
