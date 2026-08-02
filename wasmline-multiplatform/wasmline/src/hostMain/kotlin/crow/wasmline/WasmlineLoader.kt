package crow.wasmline

internal data class ResolvedPrecompiledArtifact(val artifactPath: String, val moduleKey: String)

/**
 * Shared local-artifact bridge for runtime platform actuals.
 *
 * This object intentionally stays internal to the `wasmline` runtime module.
 * Host-facing package/manifest/download/signature workflows belong in the
 * separate `wasmline-loader` module, while this bridge only centralizes the
 * final local artifact validation and host load flow used by platform actuals.
 */
internal object WasmlineLocalArtifactBridge {

    internal fun load(
        descriptor: WasmlineArtifactDescriptor,
        config: WasmlineConfig,
        platform: WasmlinePlatformArtifactBridge,
    ): WasmlineLoadState {
        val log = WasmlineLog.logger
        val validationError = descriptor.validationError()
            ?: platform.validationError(descriptor)
        if (validationError != null) {
            return WasmlineLoadState.Failure(
                code = WasmlineLoadState.CODE_FAILURE,
                cause = "[Wasmline] Invalid artifact descriptor: $validationError",
            )
        }

        val resolvedArtifact = platform.resolveArtifact(descriptor.path)
            ?: run {
                log?.warn("[WasmlineLocalArtifactBridge] Artifact file not found: ${descriptor.path}")
                return WasmlineLoadState.Failure(
                    code = WasmlineLoadState.CODE_FAILURE,
                    cause = "[Wasmline] Load failure, artifact file not found: ${descriptor.path}",
                )
            }

        val resolvedArtifactPath = resolvedArtifact.artifactPath
        val resolvedDescriptor = descriptor.copy(path = resolvedArtifactPath)
        val code = platform.backendCodeOrNull(resolvedArtifactPath, resolvedDescriptor)
            ?: run {
                val msg = platform.unsupportedArtifactMessage(resolvedDescriptor)
                log?.warn("[WasmlineLocalArtifactBridge] $msg")
                return WasmlineLoadState.Failure(
                    code = WasmlineLoadState.CODE_FAILURE,
                    cause = msg,
                )
            }

        val loadFailure = try {
            if (platform.loadPrecompiled(resolvedArtifact.moduleKey, resolvedArtifactPath, resolvedDescriptor)) {
                null
            } else {
                platform.loadFailureMessage(resolvedDescriptor)
            }
        } catch (error: Exception) {
            val baseMessage = platform.loadFailureMessage(resolvedDescriptor)
            val detail = error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName.orEmpty()
            "$baseMessage: $detail"
        }
        if (loadFailure != null) {
            val msg = loadFailure
            log?.error("[WasmlineLocalArtifactBridge] $msg")
            return WasmlineLoadState.Failure(
                code = WasmlineLoadState.CODE_FAILURE,
                cause = msg,
            )
        }

        log?.info("[WasmlineLocalArtifactBridge] Module loaded: ${resolvedArtifact.moduleKey}")
        return WasmlineLoadState.Success(
            code = code,
            wasmline = platform.createWasmline(resolvedArtifact.moduleKey, config, resolvedDescriptor),
        )
    }

    internal fun load(artifactPath: String, config: WasmlineConfig, platform: WasmlinePlatformArtifactBridge): WasmlineLoadState =
        load(WasmlineArtifactDescriptor(path = artifactPath), config, platform)
}

internal interface WasmlinePlatformArtifactBridge {
    fun createWasmline(moduleKey: String, config: WasmlineConfig, descriptor: WasmlineArtifactDescriptor): Wasmline
    fun resolveArtifact(path: String): ResolvedPrecompiledArtifact?
    fun loadPrecompiled(moduleKey: String, path: String, descriptor: WasmlineArtifactDescriptor): Boolean
    fun validationError(descriptor: WasmlineArtifactDescriptor): String? = null
    fun backendCodeOrNull(path: String, descriptor: WasmlineArtifactDescriptor): Byte? = descriptor.backendCodeOrNull(path)
    fun unsupportedArtifactMessage(descriptor: WasmlineArtifactDescriptor): String = "[Wasmline] Load failure for " +
        "${descriptor.executionModel}/${descriptor.invocationProtocol}: ${descriptor.path}"
    fun loadFailureMessage(descriptor: WasmlineArtifactDescriptor): String =
        "[Wasmline] Load failure, native artifact load returned false: " +
            descriptor.path
}

private fun WasmlineArtifactDescriptor.backendCodeOrNull(path: String): Byte? {
    val extensionCode = when (path.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
        "cwasm" -> WasmlineLoadState.CODE_SUCCESS_AOT
        "pwasm" -> WasmlineLoadState.CODE_SUCCESS_PULLEY
        "wasm" -> WasmlineLoadState.CODE_SUCCESS_WASM
        else -> null
    }
    return when (executionModel) {
        WasmlineExecutionModel.CORE_WASM -> extensionCode
        WasmlineExecutionModel.COMPONENT_MODEL -> extensionCode?.let { WasmlineLoadState.CODE_SUCCESS_COMPONENT }
    }?.let { code ->
        if (invocationProtocol == WasmlineInvocationProtocol.RAW_EXPORT) {
            WasmlineLoadState.CODE_SUCCESS_RAW_EXPORT
        } else {
            code
        }
    }
}
