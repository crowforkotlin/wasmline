@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package crow.wasmline.sample

import crow.wasmline.Wasmline
import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineComponentServiceContract
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.WasmlineLoadResult
import crow.wasmline.WasmlineRawValue
import crow.wasmline.bind
import crow.wasmline.callResult
import crow.wasmline.invokeRawResult
import crow.wasmline.link
import crow.wasmline.wasmlineNativeRuntimeInfo
import crow.wasmline.loader.WasmlineLoader
import crow.wasmline.sample.bean.PlatformBean
import crow.wasmline.sample.extensions.getPlatformBean
import crow.wasmline.sample.extensions.info
import crow.wasmline.sample.extensions.toJsonString
import crow.wasmline.sample.ir.EchoService
import crow.wasmline.sample.ir.TimeSyncService
import crow.wasmline.serialization.WasmlineSerializationConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.time.TimeSource

enum class WasmSampleMode(
    val title: String,
    val description: String,
    val protocol: String,
    val defaultExport: String,
) {
    CORE_WASM(
        title = "Core Wasm",
        description = "WasmlineService + action bridge",
        protocol = "WASMLINE_SERVICE",
        defaultExport = "TimeSyncService.timeSync",
    ),
    RAW_EXPORT(
        title = "Raw Export",
        description = "Direct numeric Core export",
        protocol = "RAW_EXPORT",
        defaultExport = "add_i32",
    ),
    COMPONENT_MODEL(
        title = "Component Model",
        description = "WIT Component export",
        protocol = "COMPONENT_EXPORT",
        defaultExport = "plugin/invoke",
    ),
}

data class SampleArtifacts(
    val corePath: String,
    val rawExportPath: String = "",
    val componentPath: String = "",
) {
    fun pathFor(mode: WasmSampleMode): String = when (mode) {
        WasmSampleMode.CORE_WASM -> corePath
        WasmSampleMode.RAW_EXPORT -> rawExportPath
        WasmSampleMode.COMPONENT_MODEL -> componentPath
    }
}

enum class WasmExecutionStatus {
    Idle,
    Running,
    Success,
    Failure,
}

data class WasmExecutionRequest(
    val mode: WasmSampleMode,
    val artifactPath: String,
    val platform: String,
    val content: String,
    val rawValue: Int,
    val timeOffsetMs: Long,
    val forceReload: Boolean,
    val freshMode: Boolean = false,
)

data class WasmExecutionReport(
    val status: WasmExecutionStatus,
    val mode: WasmSampleMode,
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
        fun idle(mode: WasmSampleMode, artifactPath: String): WasmExecutionReport = base(
            status = WasmExecutionStatus.Idle,
            mode = mode,
            artifactPath = artifactPath,
            headline = "Ready to run",
            detail = mode.description,
            loadModeLabel = "Waiting",
            logs = listOf("[Sample] idle"),
        )

        fun running(mode: WasmSampleMode, artifactPath: String): WasmExecutionReport = base(
            status = WasmExecutionStatus.Running,
            mode = mode,
            artifactPath = artifactPath,
            headline = "Running sample",
            detail = "Loading the selected artifact and invoking its declared boundary.",
            loadModeLabel = "Running",
            logs = listOf("[Sample] execution started"),
        )

        private fun base(
            status: WasmExecutionStatus,
            mode: WasmSampleMode,
            artifactPath: String,
            headline: String,
            detail: String,
            loadModeLabel: String,
            logs: List<String>,
        ): WasmExecutionReport = WasmExecutionReport(
            status = status,
            mode = mode,
            headline = headline,
            detail = detail,
            artifactPath = artifactPath,
            artifactName = artifactPath.fileName(),
            executedAction = mode.defaultExport,
            executedAt = "",
            backendLabel = "Not loaded",
            loadModeLabel = loadModeLabel,
            loadDurationMs = 0,
            invokeDurationMs = 0,
            totalDurationMs = 0,
            logs = logs,
        )
    }
}

/** Coordinates the sample's Wasmline lifecycle without exposing it to Compose UI. */
internal class WasmSampleRunner(
    private val assetRefresher: AssetRefresher,
) {
    private var wasmline: Wasmline? = null
    private var loadedKey: String? = null

    suspend fun execute(request: WasmExecutionRequest): WasmExecutionReport {
        val logs = mutableListOf<String>()
        val totalMark = TimeSource.Monotonic.markNow()
        val artifactName = request.artifactPath.fileName()
        val inputPayload = buildInputPayload(request)
        val inputJson = toJsonString(inputPayload)

        fun log(message: String) {
            logs += message
            message.info()
        }

        try {
            if (request.artifactPath.isBlank()) {
                return failure(
                    request = request,
                    artifactName = artifactName,
                    inputPayload = inputPayload,
                    inputJson = inputJson,
                    logs = logs + "[Sample] no artifact configured for ${request.mode.title}",
                    totalDurationMs = totalMark.elapsedNow().inWholeMilliseconds,
                    message = "No artifact is configured for ${request.mode.title}.",
                )
            }

            val resolvedPath = if (request.freshMode) {
                log("[Sample] refreshing ${request.artifactPath}")
                assetRefresher.refresh(request.artifactPath)
            } else {
                request.artifactPath
            }
            val key = "${request.mode.name}:$resolvedPath"
            var reloadOccurred = false
            var loadDurationMs = 0L
            var current = wasmline

            if (current != null && (request.forceReload || loadedKey != key)) {
                log("[Sample] closing cached runtime")
                closeRuntime()
                current = null
            }

            if (current == null) {
                reloadOccurred = true
                val loadMark = TimeSource.Monotonic.markNow()
                val result = load(request.mode, resolvedPath)
                loadDurationMs = loadMark.elapsedNow().inWholeMilliseconds
                when (result) {
                    is WasmlineLoadResult.Failure -> {
                        return failure(
                            request = request,
                            artifactName = artifactName,
                            inputPayload = inputPayload,
                            inputJson = inputJson,
                            logs = logs + "[Sample] load failure: ${result.cause}",
                            loadDurationMs = loadDurationMs,
                            totalDurationMs = totalMark.elapsedNow().inWholeMilliseconds,
                            message = result.cause,
                        )
                    }

                    is WasmlineLoadResult.Success -> {
                        current = result.wasmline
                        wasmline = current
                        loadedKey = key
                        log("[Sample] loaded ${request.mode.title} in ${loadDurationMs} ms")
                    }
                }
            } else {
                log("[Sample] reused cached runtime")
            }

            val runtime = checkNotNull(current)
            val invokeMark = TimeSource.Monotonic.markNow()
            val result = when (request.mode) {
                WasmSampleMode.CORE_WASM -> runCore(runtime, inputPayload, ::log)
                WasmSampleMode.RAW_EXPORT -> runRaw(runtime, request.rawValue, ::log)
                WasmSampleMode.COMPONENT_MODEL -> runComponent(runtime, request.content, ::log)
            }
            val invokeDurationMs = invokeMark.elapsedNow().inWholeMilliseconds
            val totalDurationMs = totalMark.elapsedNow().inWholeMilliseconds
            val loadMode = when {
                reloadOccurred && request.freshMode -> "Fresh reload"
                reloadOccurred && request.forceReload -> "Forced reload"
                reloadOccurred -> "Loaded"
                else -> "Cached"
            }

            return when (result) {
                is SampleInvocation.Success -> WasmExecutionReport(
                    status = WasmExecutionStatus.Success,
                    mode = request.mode,
                    headline = "Execution completed",
                    detail = result.detail,
                    artifactPath = resolvedPath,
                    artifactName = resolvedPath.fileName(),
                    executedAction = result.action,
                    executedAt = inputPayload.timeStr,
                    backendLabel = runtime.descriptor.artifactFormat?.name ?: "Manifest artifact",
                    loadModeLabel = loadMode,
                    loadDurationMs = loadDurationMs,
                    invokeDurationMs = invokeDurationMs,
                    totalDurationMs = totalDurationMs,
                    inputPayload = result.inputPayload ?: inputPayload,
                    outputPayload = result.outputPayload,
                    inputJson = result.inputJson ?: inputJson,
                    outputJson = result.outputJson,
                    logs = logs,
                )

                is SampleInvocation.Failure -> failure(
                    request = request,
                    artifactName = resolvedPath.fileName(),
                    inputPayload = inputPayload,
                    inputJson = inputJson,
                    logs = logs + "[Sample] invoke failure: ${result.message}",
                    loadDurationMs = loadDurationMs,
                    invokeDurationMs = invokeDurationMs,
                    totalDurationMs = totalDurationMs,
                    message = result.message,
                )
            }
        } catch (error: Throwable) {
            log("[Sample] exception: ${error.message ?: error::class.simpleName.orEmpty()}")
            return failure(
                request = request,
                artifactName = artifactName,
                inputPayload = inputPayload,
                inputJson = inputJson,
                logs = logs,
                totalDurationMs = totalMark.elapsedNow().inWholeMilliseconds,
                message = error.message ?: "Wasmline sample execution failed.",
            )
        }
    }

    fun close() {
        closeRuntime()
    }

    private fun load(mode: WasmSampleMode, path: String): WasmlineLoadResult {
        val config = WasmlineConfig(
            serialization = WasmlineSerializationConfig.protobuf(),
        )
        if (path.endsWith(".wlm", ignoreCase = true)) {
            return WasmlineLoader.load(source = path, config = config)
        }

        return WasmlineLoader.load(
            descriptor = descriptorFor(mode, path),
            config = config,
        )
    }

    private fun descriptorFor(mode: WasmSampleMode, path: String): WasmlineArtifactDescriptor {
        val format = path.artifactFormat()
        val descriptor = when (mode) {
            WasmSampleMode.CORE_WASM -> WasmlineArtifactDescriptor(
                path = path,
                artifactFormat = format,
                executionModel = WasmlineExecutionModel.CORE_WASM,
                invocationProtocol = WasmlineInvocationProtocol.WASMLINE_SERVICE,
            )

            WasmSampleMode.RAW_EXPORT -> WasmlineArtifactDescriptor(
                path = path,
                artifactFormat = format,
                executionModel = WasmlineExecutionModel.CORE_WASM,
                invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
                exportName = WasmSampleMode.RAW_EXPORT.defaultExport,
                contractMetadata = mapOf(
                    "params" to "s32,s32",
                    "result" to "s32",
                ),
            )

            WasmSampleMode.COMPONENT_MODEL -> WasmlineArtifactDescriptor(
                path = path,
                artifactFormat = format,
                executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
                invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
                exportName = WasmSampleMode.COMPONENT_MODEL.defaultExport,
                contractMetadata = mapOf(
                    WasmlineComponentServiceContract.METADATA_PROFILE to WasmlineComponentServiceContract.PROFILE,
                    WasmlineComponentServiceContract.METADATA_CODEC to WasmlineComponentServiceContract.DEFAULT_CODEC,
                    WasmlineComponentServiceContract.METADATA_VERSION to WasmlineComponentServiceContract.VERSION,
                ),
            )
        }
        return descriptor.withNativeArtifactMetadata()
    }

    private fun WasmlineArtifactDescriptor.withNativeArtifactMetadata(): WasmlineArtifactDescriptor {
        val format = artifactFormat ?: return this
        if (format == WasmlineArtifactFormat.RAW_WASM) return this

        val runtime = wasmlineNativeRuntimeInfo()
            ?: error("Native runtime metadata is unavailable for AOT artifact '$path'. Use a raw .wasm artifact on browser runtimes.")
        val runtimeBitness = runtime.is64Bit
            ?: error("Native runtime did not report bitness for AOT artifact '$path'.")

        return copy(
            targetCompilerVersion = "wasmtime-${runtime.wasmtimeVersion}",
            targetCpu = when (format) {
                WasmlineArtifactFormat.PWASM -> if (runtimeBitness) "pulley64" else "pulley32"
                WasmlineArtifactFormat.CWASM -> runtime.targetCpu
                    ?: error("Native runtime did not report CPU for CWASM artifact '$path'.")
                WasmlineArtifactFormat.RAW_WASM -> null
            },
            targetOs = when (format) {
                WasmlineArtifactFormat.CWASM -> runtime.targetOs
                    ?: error("Native runtime did not report OS for CWASM artifact '$path'.")
                WasmlineArtifactFormat.PWASM, WasmlineArtifactFormat.RAW_WASM -> null
            },
            is64Bit = runtimeBitness,
        )
    }

    private fun runCore(
        runtime: Wasmline,
        input: PlatformBean,
        log: (String) -> Unit,
    ): SampleInvocation {
        runtime.bind(object : EchoService {
            override fun echo() {
                log("[Core Wasm] plugin called host EchoService.echo")
            }
        })
        val result = runtime.link<TimeSyncService>().timeSync(input)
        log("[Core Wasm] invoked TimeSyncService.timeSync")
        return SampleInvocation.Success(
            action = "TimeSyncService.timeSync",
            detail = "Core Wasmline service call completed.",
            inputPayload = input,
            outputPayload = result,
            inputJson = toJsonString(input),
            outputJson = toJsonString(result),
        )
    }

    private fun runRaw(
        runtime: Wasmline,
        value: Int,
        log: (String) -> Unit,
    ): SampleInvocation {
        val result = runtime.invokeRawResult(
            exportName = WasmSampleMode.RAW_EXPORT.defaultExport,
            arguments = listOf(
                WasmlineRawValue.I32(value),
                WasmlineRawValue.I32(1),
            ),
        )
        return when (result) {
            is crow.wasmline.invocation.WasmlineCallResult.Failure ->
                SampleInvocation.Failure(result.error.message)

            is crow.wasmline.invocation.WasmlineCallResult.Success -> {
                log("[Raw Export] invoked add_i32($value, 1)")
                val output = result.value.values.singleOrNull()
                    ?: return SampleInvocation.Failure("Raw export returned no value.")
                SampleInvocation.Success(
                    action = "add_i32",
                    detail = "Direct Core Wasm export call completed.",
                    inputJson = "{\"value\":$value,\"increment\":1}",
                    outputJson = "{\"value\":${rawValueText(output)}}",
                )
            }
        }
    }

    private fun runComponent(
        runtime: Wasmline,
        content: String,
        log: (String) -> Unit,
    ): SampleInvocation {
        val request = ComponentEchoRequest(value = content.ifBlank { "hello" })
        val payload = ProtoBuf.encodeToByteArray(ComponentEchoRequest.serializer(), request)
        val result = runtime.callResult(action = "sample.echo", payload = payload)
        return when (result) {
            is crow.wasmline.invocation.WasmlineCallResult.Failure ->
                SampleInvocation.Failure(result.error.message)

            is crow.wasmline.invocation.WasmlineCallResult.Success -> {
                val response = ProtoBuf.decodeFromByteArray(
                    ComponentEchoResponse.serializer(),
                    result.value,
                )
                log("[Component Model] invoked plugin/invoke via WIT envelope")
                SampleInvocation.Success(
                    action = "plugin/invoke -> sample.echo",
                    detail = "Component Model export call completed.",
                    inputJson = toJsonString(request),
                    outputJson = toJsonString(response),
                )
            }
        }
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

    private fun closeRuntime() {
        wasmline?.close()
        wasmline = null
        loadedKey = null
    }

    private fun failure(
        request: WasmExecutionRequest,
        artifactName: String,
        inputPayload: PlatformBean,
        inputJson: String,
        logs: List<String>,
        message: String,
        loadDurationMs: Long = 0,
        invokeDurationMs: Long = 0,
        totalDurationMs: Long = 0,
    ): WasmExecutionReport = WasmExecutionReport(
        status = WasmExecutionStatus.Failure,
        mode = request.mode,
        headline = "Execution failed",
        detail = "The selected Wasm boundary did not return a successful result.",
        artifactPath = request.artifactPath,
        artifactName = artifactName,
        executedAction = request.mode.defaultExport,
        executedAt = inputPayload.timeStr,
        backendLabel = "Load failed",
        loadModeLabel = "Failed",
        loadDurationMs = loadDurationMs,
        invokeDurationMs = invokeDurationMs,
        totalDurationMs = totalDurationMs,
        inputPayload = inputPayload,
        inputJson = inputJson,
        errorMessage = message,
        logs = logs,
    )
}

private sealed interface SampleInvocation {
    data class Success(
        val action: String,
        val detail: String,
        val inputPayload: PlatformBean? = null,
        val outputPayload: PlatformBean? = null,
        val inputJson: String? = null,
        val outputJson: String,
    ) : SampleInvocation

    data class Failure(val message: String) : SampleInvocation
}

@Serializable
private data class ComponentEchoRequest(val value: String)

@Serializable
private data class ComponentEchoResponse(val value: String)

private fun rawValueText(value: WasmlineRawValue): String = when (value) {
    is WasmlineRawValue.I32 -> value.value.toString()
    is WasmlineRawValue.I64 -> value.value.toString()
    is WasmlineRawValue.F32 -> value.value.toString()
    is WasmlineRawValue.F64 -> value.value.toString()
}

private fun String.artifactFormat(): WasmlineArtifactFormat = when {
    endsWith(".wasm", ignoreCase = true) -> WasmlineArtifactFormat.RAW_WASM
    endsWith(".cwasm", ignoreCase = true) -> WasmlineArtifactFormat.CWASM
    endsWith(".pwasm", ignoreCase = true) -> WasmlineArtifactFormat.PWASM
    else -> error("Artifact must end with .wasm, .cwasm, or .pwasm: $this")
}

private fun String.fileName(): String =
    substringAfterLast('/').substringAfterLast('\\').ifBlank { "—" }
