package crow.wasmline.samples.minimal.library.ui

import androidx.compose.material.MaterialTheme
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import crow.wasmline.samples.minimal.library.model.MinimalExample
import crow.wasmline.samples.minimal.library.runtime.PluginEnvironment
import crow.wasmline.samples.minimal.library.runtime.PluginRunner
import crow.wasmline.samples.minimal.library.runtime.RunController
import kotlinx.coroutines.launch

@Composable
fun MinimalApp(example: MinimalExample, environment: PluginEnvironment) {
    val controller = remember(example, environment) {
        RunController(example.initialText, PluginRunner(environment, example)::run)
    }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    MaterialTheme(colors = lightColors(primary = example.accent)) {
        MinimalScreen(state = state, onRun = { scope.launch { controller.run() } })
    }
}
