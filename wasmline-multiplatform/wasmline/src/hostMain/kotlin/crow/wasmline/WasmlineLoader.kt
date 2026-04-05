package crow.wasmline

internal fun loadWasmlineModule(
    sourcePath: String,
    cachePath: String?,
    key: String,
    createWasmline: (String) -> Wasmline,
    fileExists: (String) -> Boolean,
    deleteFile: (String) -> Unit,
    loadAot: (String, String) -> Boolean,
    loadJit: (String, String) -> Boolean,
    saveCache: ((String, String) -> Boolean)? = null,
    jitFailureMessage: (String) -> String = { path ->
        "[Wasmline] Load failure, because native load return false, file path is :  $path"
    },
): WasmlineLoadState {
    if (cachePath != null && fileExists(cachePath)) {
        if (loadAot(key, cachePath)) {
            return WasmlineLoadState.Success(
                code = WasmlineLoadState.CODE_SUCCESS_AOT,
                wasmline = createWasmline(key),
            )
        }
        deleteFile(cachePath)
    }

    if (!fileExists(sourcePath)) {
        return WasmlineLoadState.Failure(
            code = WasmlineLoadState.CODE_FAILURE,
            cause = "[Wasmline] Load failure, file not found: $sourcePath",
        )
    }

    if (!loadJit(key, sourcePath)) {
        return WasmlineLoadState.Failure(
            code = WasmlineLoadState.CODE_FAILURE,
            cause = jitFailureMessage(sourcePath),
        )
    }

    if (cachePath != null) {
        saveCache?.invoke(key, cachePath)
    }

    return WasmlineLoadState.Success(
        code = WasmlineLoadState.CODE_SUCCESS_JIT,
        wasmline = createWasmline(key),
    )
}

