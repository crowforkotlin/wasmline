package crow.wasmline

internal data class ResolvedPrecompiledArtifact(
    val artifactPath: String,
    val moduleKey: String,
)

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
        artifactPath: String,
        platform: WasmlinePlatformArtifactBridge
    ): WasmlineLoadState {
        val resolvedArtifact = platform.resolveArtifact(artifactPath)
            ?: return WasmlineLoadState.Failure(
                code = WasmlineLoadState.CODE_FAILURE,
                cause = "[Wasmline] Load failure, artifact file not found: $artifactPath",
            )

        val resolvedArtifactPath = resolvedArtifact.artifactPath
        val code = platform.backendCodeOrNull(resolvedArtifactPath)
            ?: return WasmlineLoadState.Failure(
                code = WasmlineLoadState.CODE_FAILURE,
                cause = platform.unsupportedArtifactMessage(resolvedArtifactPath),
            )

        if (!platform.loadPrecompiled(resolvedArtifact.moduleKey, resolvedArtifactPath)) {
            return WasmlineLoadState.Failure(
                code = WasmlineLoadState.CODE_FAILURE,
                cause = platform.loadFailureMessage(resolvedArtifactPath),
            )
        }

        return WasmlineLoadState.Success(
            code = code,
            wasmline = platform.createWasmline(resolvedArtifact.moduleKey),
        )
    }
}

internal interface WasmlinePlatformArtifactBridge {
    fun createWasmline(moduleKey: String): Wasmline
    fun resolveArtifact(path: String): ResolvedPrecompiledArtifact?
    fun loadPrecompiled(moduleKey: String, path: String): Boolean
    fun backendCodeOrNull(path: String): Byte? = path.precompiledBackendCodeOrNull()
    fun unsupportedArtifactMessage(path: String): String =
        "[Wasmline] Load failure, only .cwasm or .pwasm artifacts are supported on Wasmtime hosts: $path"
    fun loadFailureMessage(path: String): String =
        "[Wasmline] Load failure, because native load return false, artifact path is : $path"
}

private fun String.precompiledBackendCodeOrNull(): Byte? {
    return when (substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
        "cwasm" -> WasmlineLoadState.CODE_SUCCESS_AOT
        "pwasm" -> WasmlineLoadState.CODE_SUCCESS_PULLEY
        else -> null
    }
}
