package crow.wasmline.minimal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import crow.wasmline.RawValue
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invokeRawResult
import crow.wasmline.samples.minimal.MinimalApp
import crow.wasmline.samples.minimal.MinimalExample
import crow.wasmline.samples.minimal.PluginEnvironment

@Composable
fun App(environment: PluginEnvironment) {
    val example = remember {
        MinimalExample(
            initialText = "19 + 23",
            accent = Color(0xFF2463EB),
            invocation = { module ->
                println("[host] add_i32(19, 23)")
                val result = module.invokeRawResult("add_i32", listOf(RawValue.I32(19), RawValue.I32(23)))
                val value = when (result) {
                    is WasmlineCallResult.Failure -> error(result.failure.message)

                    is WasmlineCallResult.Success ->
                        (result.value.values.singleOrNull() as? RawValue.I32)?.value
                            ?: error("Expected one i32 result.")
                }
                "19 + 23 = $value"
            },
        )
    }
    MinimalApp(example, environment)
}
