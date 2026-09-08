package crow.wasmline.samples.minimal

import crow.wasmline.WasmlineLoadResult
import kotlinx.coroutines.CoroutineDispatcher

class PluginEnvironment(val dispatcher: CoroutineDispatcher, val load: suspend () -> WasmlineLoadResult)
