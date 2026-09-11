package crow.wasmline.samples.minimal.library.runtime

import crow.wasmline.WasmlineLoadResult
import crow.wasmline.WasmlineRuntime
import crow.wasmline.samples.minimal.library.model.MinimalExample
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// The sample owns the process runtime. Serialize even across Android screen recreation.
private val runtimeMutex = Mutex()

class PluginRunner(private val environment: PluginEnvironment, private val example: MinimalExample) {
    suspend fun run(): String = withContext(environment.dispatcher) {
        runtimeMutex.withLock {
            try {
                val module = when (val loaded = environment.load()) {
                    is WasmlineLoadResult.Success -> loaded.wasmline
                    is WasmlineLoadResult.Failure -> error(loaded.failure.message)
                }
                try {
                    example.invocation(module)
                } finally {
                    module.close()
                }
            } finally {
                WasmlineRuntime.shutdown()
            }
        }
    }
}
