package crow.wasmline.samples.minimal.library.runtime

import crow.wasmline.samples.minimal.library.model.RunState
import crow.wasmline.samples.minimal.library.model.RunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

class RunController(initialText: String, private val execute: suspend () -> String, private val log: (String) -> Unit = ::println) {
    private val executionMutex = Mutex()
    private val mutableState = MutableStateFlow(RunState(initialText))
    val state: StateFlow<RunState> = mutableState.asStateFlow()

    suspend fun run() {
        if (!executionMutex.tryLock()) return
        val previousState = mutableState.value
        try {
            mutableState.value = RunState("Running...", RunStatus.Running)
            log("[host] Execute clicked")
            val result = execute()
            log("[host] Result: $result")
            mutableState.value = RunState(result, RunStatus.Success)
        } catch (cancelled: CancellationException) {
            mutableState.value = previousState
            throw cancelled
        } catch (error: Throwable) {
            log("[host] Failure: ${error.stackTraceToString()}")
            mutableState.value = RunState(error.message ?: "Invocation failed", RunStatus.Failure)
        } finally {
            executionMutex.unlock()
        }
    }
}
