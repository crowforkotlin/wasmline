package crow.wasmline

import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invocation.WasmlineFailure

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
        val requiredFormatError = if (platform.requiresExplicitArtifactFormat() && descriptor.artifactFormat == null) {
            "Native artifact loading requires an explicit artifactFormat."
        } else {
            null
        }
        val validationFailure = when {
            descriptor.validationError() != null -> WasmlineFailure(
                code = WasmlineErrorCode.ARTIFACT_DESCRIPTOR_INVALID,
                message = "[Wasmline] Invalid artifact descriptor: ${descriptor.validationError()}",
            )

            requiredFormatError != null -> WasmlineFailure(
                code = WasmlineErrorCode.ARTIFACT_DESCRIPTOR_INVALID,
                message = "[Wasmline] Invalid artifact descriptor: $requiredFormatError",
            )

            else -> platform.validationFailure(descriptor)
        }
        if (validationFailure != null) {
            return loadFailure(
                stage = WasmlineLoadStage.ARTIFACT_VALIDATION,
                code = validationFailure.code,
                message = validationFailure.message,
                details = validationFailure.details,
                rawCode = validationFailure.rawCode,
            ).toLoadState()
        }

        val resolvedArtifact = platform.resolveArtifact(descriptor.path)
            ?: run {
                log?.warn("[WasmlineLocalArtifactBridge] Artifact file not found: ${descriptor.path}")
                return loadFailure(
                    stage = WasmlineLoadStage.ARTIFACT_RESOLUTION,
                    code = WasmlineErrorCode.ARTIFACT_NOT_FOUND,
                    message = "[Wasmline] Load failure, artifact file not found: ${descriptor.path}",
                ).toLoadState()
            }

        val resolvedArtifactPath = resolvedArtifact.artifactPath
        val resolvedDescriptor = descriptor.copy(path = resolvedArtifactPath)
        val code = platform.backendCodeOrNull(resolvedArtifactPath, resolvedDescriptor)
            ?: run {
                val failure = platform.unsupportedArtifactFailure(resolvedDescriptor)
                log?.warn("[WasmlineLocalArtifactBridge] ${failure.message}")
                return loadFailure(
                    stage = WasmlineLoadStage.ARTIFACT_SELECTION,
                    code = failure.code,
                    message = failure.message,
                    details = failure.details,
                    rawCode = failure.rawCode,
                ).toLoadState()
            }

        val loadFailure = try {
            if (platform.loadPrecompiled(resolvedArtifact.moduleKey, resolvedArtifactPath, resolvedDescriptor)) {
                null
            } else {
                platform.loadFailureValue(resolvedDescriptor)
            }
        } catch (error: Exception) {
            val baseMessage = platform.loadFailureMessage(resolvedDescriptor)
            val detail = error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName.orEmpty()
            WasmlineFailure(
                code = WasmlineErrorCode.MODULE_FORMAT_INVALID,
                message = "$baseMessage: $detail",
                details = detail.encodeToByteArray(),
            )
        }
        if (loadFailure != null) {
            log?.error("[WasmlineLocalArtifactBridge] ${loadFailure.message}")
            return loadFailure(
                stage = WasmlineLoadStage.MODULE_CREATION,
                code = loadFailure.code,
                message = loadFailure.message,
                details = loadFailure.details,
                rawCode = loadFailure.rawCode,
            ).toLoadState()
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
    fun requiresExplicitArtifactFormat(): Boolean = false
    fun backendCodeOrNull(path: String, descriptor: WasmlineArtifactDescriptor): Byte? = descriptor.backendCodeOrNull()
    fun unsupportedArtifactMessage(descriptor: WasmlineArtifactDescriptor): String = "[Wasmline] Load failure for " +
        "${descriptor.executionModel}/${descriptor.invocationProtocol}: ${descriptor.path}"
    fun loadFailureMessage(descriptor: WasmlineArtifactDescriptor): String =
        "[Wasmline] Load failure, native artifact load returned false: " +
            descriptor.path

    /** Provides a structured platform validation failure. */
    fun validationFailure(descriptor: WasmlineArtifactDescriptor): WasmlineFailure? = validationError(descriptor)?.let { message ->
        WasmlineFailure(WasmlineErrorCode.ARTIFACT_NOT_COMPATIBLE, message)
    }

    /** Provides a structured failure when the platform cannot select the artifact. */
    fun unsupportedArtifactFailure(descriptor: WasmlineArtifactDescriptor): WasmlineFailure = WasmlineFailure(
        code = WasmlineErrorCode.ARTIFACT_NOT_COMPATIBLE,
        message = unsupportedArtifactMessage(descriptor),
    )

    /** Provides a structured failure when native module creation returns false. */
    fun loadFailureValue(descriptor: WasmlineArtifactDescriptor): WasmlineFailure = WasmlineFailure(
        code = WasmlineErrorCode.MODULE_FORMAT_INVALID,
        message = loadFailureMessage(descriptor),
    )
}

internal fun WasmlineArtifactDescriptor.backendCodeOrNull(): Byte? {
    val formatCode = when (artifactFormat) {
        WasmlineArtifactFormat.RAW_WASM -> WasmlineLoadState.CODE_SUCCESS_WASM
        WasmlineArtifactFormat.CWASM -> WasmlineLoadState.CODE_SUCCESS_AOT
        WasmlineArtifactFormat.PWASM -> WasmlineLoadState.CODE_SUCCESS_PULLEY
        null -> return null
    }
    return when (executionModel) {
        WasmlineExecutionModel.CORE_WASM -> formatCode
        WasmlineExecutionModel.COMPONENT_MODEL -> WasmlineLoadState.CODE_SUCCESS_COMPONENT
    }.let { code ->
        if (invocationProtocol == WasmlineInvocationProtocol.RAW_EXPORT) {
            WasmlineLoadState.CODE_SUCCESS_RAW_EXPORT
        } else {
            code
        }
    }
}
