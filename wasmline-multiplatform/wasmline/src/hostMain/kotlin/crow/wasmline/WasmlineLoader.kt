package crow.wasmline

internal data class ResolvedPrecompiledArtifact(
    val artifactPath: String,
    val moduleKey: String,
)

/**
 * Shared local precompiled-artifact bridge for runtime platform actuals.
 *
 * This object intentionally stays internal to the `wasmline` runtime module.
 * Host-facing package/manifest/download/signature workflows belong in the
 * separate `wasmline-loader` module, while this bridge only centralizes the
 * final local artifact validation and native load flow used by JNI/iOS actuals.
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
        val code = resolvedArtifactPath.backendCodeOrNull()
            ?: return WasmlineLoadState.Failure(
                code = WasmlineLoadState.CODE_FAILURE,
                cause = "[Wasmline] Load failure, only .cwasm or .pwasm artifacts are supported: $resolvedArtifactPath",
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
    fun loadFailureMessage(path: String): String =
        "[Wasmline] Load failure, because native load return false, artifact path is : $path"
}

private fun String.backendCodeOrNull(): Byte? {
    return when (substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
        "cwasm" -> WasmlineLoadState.CODE_SUCCESS_AOT
        "pwasm" -> WasmlineLoadState.CODE_SUCCESS_PULLEY
        else -> null
    }
}

