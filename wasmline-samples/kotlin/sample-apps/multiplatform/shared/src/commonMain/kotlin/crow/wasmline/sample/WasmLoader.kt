@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package crow.wasmline.sample

import crow.wasmline.Wasmline
import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineHttpResponse
import crow.wasmline.WasmlineLoadResult
import crow.wasmline.WasmlineNetworkClient
import crow.wasmline.bind
import crow.wasmline.link
import crow.wasmline.loader.WasmlineLoader
import crow.wasmline.sample.bean.PlatformBean
import crow.wasmline.sample.extensions.getPlatformBean
import crow.wasmline.sample.extensions.info
import crow.wasmline.sample.extensions.toJsonString
import crow.wasmline.sample.ir.EchoService
import crow.wasmline.sample.ir.TimeSyncService
import crow.wasmline.serialization.WasmlineSerializationConfig
import kotlin.time.TimeSource
import kotlin.time.measureTime

internal enum class WasmExecutionStatus {
    Idle,
    Running,
    Success,
    Failure,
}

internal data class WasmExecutionRequest(
    val artifactPath: String,
    val platform: String,
    val content: String,
    val timeOffsetMs: Long,
    val forceReload: Boolean,
    val executedAction: String = "TimeSyncService.timeSync",
)

internal data class WasmExecutionReport(
    val status: WasmExecutionStatus,
    val headline: String,
    val detail: String,
    val artifactPath: String,
    val artifactName: String,
    val executedAction: String,
    val executedAt: String,
    val backendLabel: String,
    val loadModeLabel: String,
    val loadDurationMs: Long,
    val invokeDurationMs: Long,
    val totalDurationMs: Long,
    val inputPayload: PlatformBean? = null,
    val outputPayload: PlatformBean? = null,
    val inputJson: String = "",
    val outputJson: String = "",
    val errorMessage: String = "",
    val logs: List<String> = emptyList(),
) {
    val consoleLog: String
        get() = if (logs.isEmpty()) "// waiting for logs..." else logs.joinToString(separator = "\n")

    companion object {
        fun idle(artifactPath: String): WasmExecutionReport {
            return WasmExecutionReport(
                status = WasmExecutionStatus.Idle,
                headline = "Ready to execute",
                detail = "调整参数后即可触发 TimeSyncService.timeSync。",
                artifactPath = artifactPath,
                artifactName = artifactPath.fileName(),
                executedAction = "TimeSyncService.timeSync",
                executedAt = "",
                backendLabel = "Not loaded",
                loadModeLabel = "Waiting for execution",
                loadDurationMs = 0,
                invokeDurationMs = 0,
                totalDurationMs = 0,
                logs = listOf("[WasmLoader] idle"),
            )
        }

        fun running(artifactPath: String): WasmExecutionReport {
            return WasmExecutionReport(
                status = WasmExecutionStatus.Running,
                headline = "Executing request...",
                detail = "正在加载 artifact 并调用 TimeSyncService.timeSync。",
                artifactPath = artifactPath,
                artifactName = artifactPath.fileName(),
                executedAction = "TimeSyncService.timeSync",
                executedAt = "",
                backendLabel = "Preparing runtime",
                loadModeLabel = "Running",
                loadDurationMs = 0,
                invokeDurationMs = 0,
                totalDurationMs = 0,
                logs = listOf("[WasmLoader] execution started"),
            )
        }
    }
}

internal class WasmLoader {
    private var wasmline: Wasmline? = null
    private var loadedArtifactPath: String? = null
    private var loadedBackendCode: Byte? = null

    fun execute(request: WasmExecutionRequest): WasmExecutionReport {
        val logs = mutableListOf<String>()
        val totalMark = TimeSource.Monotonic.markNow()
        val inputPayload = buildInputPayload(request)
        val artifactName = request.artifactPath.fileName()

        fun log(message: String) {
            logs += message
            message.info()
        }

        log("[WasmLoader] execute action=${request.executedAction}")
        log("[WasmLoader] artifact=${request.artifactPath}")
        log("[WasmLoader] input=${toJsonString(inputPayload)}")

        var current = wasmline
        var reloadOccurred = false
        var loadDurationMs = 0L
        var invokeDurationMs = 0L

        if (current != null && (request.forceReload || loadedArtifactPath != request.artifactPath)) {
            log("[WasmLoader] closing cached runtime for ${loadedArtifactPath.orEmpty()}")
            current.close()
            wasmline = null
            current = null
            loadedArtifactPath = null
            loadedBackendCode = null
        }

        if (current == null) {
            reloadOccurred = true
            var result: WasmlineLoadResult? = null
            val duration = measureTime {
                result = WasmlineLoader.load(
                    source = request.artifactPath,
                    config = WasmlineConfig(
                        serialization = WasmlineSerializationConfig.protobuf(),
                    ),
                )
            }
            loadDurationMs = duration.inWholeMilliseconds

            when (val r = result ?: error("Wasmline load result is null.")) {
                is WasmlineLoadResult.Success -> {
                    r.wasmline.bind(object : EchoService {
                        override fun echo() {
                            log("[WasmLoader] plugin invoked host echo()")
                        }
                    })
                    current = r.wasmline
                    wasmline = r.wasmline
                    loadedArtifactPath = request.artifactPath
                    loadedBackendCode = null
                    log("[WasmLoader] load success in ${loadDurationMs} ms")
                }

                is WasmlineLoadResult.Failure -> {
                    log("[WasmLoader] load failure: ${r.cause}")
                    return WasmExecutionReport(
                        status = WasmExecutionStatus.Failure,
                        headline = "Execution failed",
                        detail = "运行时加载失败，未能进入 service 调用阶段。",
                        artifactPath = request.artifactPath,
                        artifactName = artifactName,
                        executedAction = request.executedAction,
                        executedAt = inputPayload.timeStr,
                        backendLabel = "Load failed",
                        loadModeLabel = if (request.forceReload) "Forced reload" else "Fresh load",
                        loadDurationMs = loadDurationMs,
                        invokeDurationMs = 0,
                        totalDurationMs = totalMark.elapsedNow().inWholeMilliseconds,
                        inputPayload = inputPayload,
                        inputJson = toJsonString(inputPayload),
                        errorMessage = r.cause,
                        logs = logs,
                    )
                }
            }
        } else {
            log("[WasmLoader] reuse cached runtime for ${request.artifactPath}")
        }

        val runtime = checkNotNull(current) { "Wasmline runtime is unavailable after load." }
        var outputPayload: PlatformBean? = null
        val invokeDuration = measureTime {
            outputPayload = runtime.link<TimeSyncService>().timeSync(inputPayload)
        }
        invokeDurationMs = invokeDuration.inWholeMilliseconds
        val totalDurationMs = totalMark.elapsedNow().inWholeMilliseconds
        val backend = backendLabel(loadedBackendCode)
        val loadMode = when {
            reloadOccurred && request.forceReload -> "Forced reload"
            reloadOccurred -> "Fresh load"
            else -> "Reused cached runtime"
        }

        log("[WasmLoader] invoke success in ${invokeDurationMs} ms")
        log("[WasmLoader] output=${toJsonString(outputPayload)}")

        return WasmExecutionReport(
            status = WasmExecutionStatus.Success,
            headline = "Execution completed",
            detail = "成功完成 artifact 加载与 service 调用。",
            artifactPath = request.artifactPath,
            artifactName = artifactName,
            executedAction = request.executedAction,
            executedAt = inputPayload.timeStr,
            backendLabel = backend,
            loadModeLabel = loadMode,
            loadDurationMs = loadDurationMs,
            invokeDurationMs = invokeDurationMs,
            totalDurationMs = totalDurationMs,
            inputPayload = inputPayload,
            outputPayload = outputPayload,
            inputJson = toJsonString(inputPayload),
            outputJson = toJsonString(outputPayload),
            logs = logs,
        )
    }

    private fun buildInputPayload(request: WasmExecutionRequest): PlatformBean {
        val base = getPlatformBean()
        return base.copy(
            platform = request.platform.ifBlank { base.platform },
            content = request.content.ifBlank { base.content },
            timeStr = base.timeStr + if (request.timeOffsetMs == 0L) "" else " (offset ${request.timeOffsetMs / 1000}s)",
            timeMs = base.timeMs + request.timeOffsetMs,
        )
    }
}

private fun backendLabel(code: Byte?): String {
    return "Wasmline"
}

private fun String.fileName(): String {
    return substringAfterLast('/').substringAfterLast('\\')
}
