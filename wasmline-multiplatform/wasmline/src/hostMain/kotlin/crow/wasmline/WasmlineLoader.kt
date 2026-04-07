package crow.wasmline

internal data class WasmlineLocalLoadRequest(
    val artifactPath: String,
    val threadSafe: Boolean = false,
)

/**
 * Shared local-file loading helper for runtime platform bridges.
 *
 * This object intentionally stays internal to the `wasmline` runtime module.
 * Host-facing package/manifest/download/signature workflows belong in the
 * separate `wasmline-loader` module, while this helper only centralizes the
 * native local precompiled-artifact loading flow used by JNI/iOS actuals.
 */
internal object WasmlineRuntimeLoader {
    internal fun load(request: WasmlineLocalLoadRequest, platform: WasmlinePlatformLoader): WasmlineLoadState {
        val artifactPath = platform.normalizePath(request.artifactPath)
        return loadLocal(
            request = LocalWasmlineLoadRequest(
                artifactPath = artifactPath,
                key = platform.moduleKeyFor(artifactPath),
            ),
            platform = platform,
        )
    }

    internal fun loadLocal(
        request: LocalWasmlineLoadRequest,
        platform: WasmlinePlatformLoader,
    ): WasmlineLoadState {
        if (!platform.fileExists(request.artifactPath)) {
            return WasmlineLoadState.Failure(
                code = WasmlineLoadState.CODE_FAILURE,
                cause = "[Wasmline] Load failure, artifact file not found: ${request.artifactPath}",
            )
        }

        val code = request.artifactPath.backendCodeOrNull()
            ?: return WasmlineLoadState.Failure(
                code = WasmlineLoadState.CODE_FAILURE,
                cause = "[Wasmline] Load failure, only .cwasm or .pwasm artifacts are supported: ${request.artifactPath}",
            )

        if (!platform.loadPrecompiled(request.key, request.artifactPath)) {
            return WasmlineLoadState.Failure(
                code = WasmlineLoadState.CODE_FAILURE,
                cause = platform.loadFailureMessage(request.artifactPath),
            )
        }

        return WasmlineLoadState.Success(
            code = code,
            wasmline = platform.createWasmline(request.key),
        )
    }
}

internal data class LocalWasmlineLoadRequest(
    val artifactPath: String,
    val key: String,
)

internal interface WasmlinePlatformLoader {
    fun createWasmline(key: String): Wasmline
    fun normalizePath(path: String): String = path
    fun moduleKeyFor(sourcePath: String): String = sourcePath
    fun fileExists(path: String): Boolean
    fun loadPrecompiled(key: String, path: String): Boolean
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

