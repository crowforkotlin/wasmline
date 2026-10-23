package crow.wasmline.samples.minimal.rawexport

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import crow.wasmline.CoreWasmSessionOptions
import crow.wasmline.RawFunctionSignature
import crow.wasmline.RawImport
import crow.wasmline.RawValue
import crow.wasmline.RawValueType
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.samples.minimal.library.model.MinimalExample
import crow.wasmline.samples.minimal.library.runtime.PluginEnvironment
import crow.wasmline.samples.minimal.library.ui.MinimalApp
import kotlin.random.Random

@Composable
fun App(environment: PluginEnvironment) {
    val example = remember {
        MinimalExample(
            initialText = "19 + 23",
            accent = Color(0xFF2463EB),
            invocation = { module ->
                println("[host] add_i32(19, 23)")
                module.asCoreWasmModule().getOrThrow().use { rawModule ->
                    val options = CoreWasmSessionOptions(
                        imports = listOf(
                            RawImport(
                                module = "wasi_snapshot_preview1",
                                name = "random_get",
                                signature = RawFunctionSignature(
                                    parameters = listOf(RawValueType.I32, RawValueType.I32),
                                    results = listOf(RawValueType.I32),
                                ),
                            ) { context, arguments ->
                                val memory = requireNotNull(context.memory) { "random_get requires exported memory." }
                                val offset = (arguments[0] as RawValue.I32).value.toUInt().toLong()
                                val length = (arguments[1] as RawValue.I32).value
                                memory.write(offset, Random.nextBytes(length)).getOrThrow()
                                WasmlineCallResult.Success(listOf(RawValue.I32(0)))
                            },
                        ),
                    )
                    rawModule.instantiate(options).getOrThrow().use { session ->
                        val values = session.invoke("add_i32", listOf(RawValue.I32(19), RawValue.I32(23))).getOrThrow()
                        val value = (values.singleOrNull() as? RawValue.I32)?.value
                            ?: error("Expected one i32 result.")
                        "19 + 23 = $value"
                    }
                }
            },
        )
    }
    MinimalApp(example, environment)
}
