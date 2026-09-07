package crow.wasmline.samples.minimal

enum class RunStatus { Idle, Running, Success, Failure }

data class RunState(val text: String, val status: RunStatus = RunStatus.Idle)
