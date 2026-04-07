package crow.wasmline.loader

import crow.wasmline.Wasmline
import crow.wasmline.WasmlineLoadState

/**
 * Public host-side loader SPI.
 *
 * This interface belongs to the loader layer because Host-facing load
 * workflows should no longer be modeled as raw `Wasmline.load(path)` calls.
 *
 * 2026-04-08
 * @author crowforkotlin
 */
fun interface WasmlineLoader {
    fun load(request: WasmlineLoadRequest): WasmlineLoadState
}

/**
 * Preferred Host-side loading entrypoint for V2.
 *
 * Callers may supply a custom [WasmlineLoader], but the default path delegates
 * to [DefaultWasmlineLoader] so Host code can migrate away from directly
 * calling `Wasmline.load(path)`.
 */
fun loadWasmline(
    request: WasmlineLoadRequest,
    loader: WasmlineLoader = DefaultWasmlineLoader,
): WasmlineLoadState {
    return loader.load(request)
}

/**
 * Convenience overload for the current local precompiled-artifact workflow.
 */
fun loadWasmline(
    artifactPath: String,
    threadSafe: Boolean = false,
    loader: WasmlineLoader = DefaultWasmlineLoader,
): WasmlineLoadState {
    return loadWasmline(
        request = WasmlineLoadRequest(
            source = WasmlineSource.LocalArtifactFile(path = artifactPath),
            threadSafe = threadSafe,
        ),
        loader = loader,
    )
}

/**
 * Minimal Host loader for the current V2 phase.
 *
 * Today the runtime only accepts prepared local `.cwasm` / `.pwasm`
 * artifacts. Package and remote sources are intentionally modeled here first,
 * but remain unsupported until manifest/package resolution is implemented.
 *
 * 2026-04-08
 * @author crowforkotlin
 */
object DefaultWasmlineLoader : WasmlineLoader {
    override fun load(request: WasmlineLoadRequest): WasmlineLoadState {
        return when (val source = request.source) {
            is WasmlineSource.LocalArtifactFile -> Wasmline.load(
                filepath = source.path,
                threadSafe = request.threadSafe,
            )

            is WasmlineSource.LocalPackageFile -> unsupportedSourceFailure(
                description = "Local package source '${source.path}'",
            )

            is WasmlineSource.RemotePackageUrl -> unsupportedSourceFailure(
                description = "Remote package source '${source.url}'",
            )
        }
    }

    private fun unsupportedSourceFailure(description: String): WasmlineLoadState.Failure {
        return WasmlineLoadState.Failure(
            code = WasmlineLoadState.CODE_FAILURE,
            cause = "$description is not supported yet. Provide a local precompiled .cwasm or .pwasm artifact for the current runtime.",
        )
    }
}

