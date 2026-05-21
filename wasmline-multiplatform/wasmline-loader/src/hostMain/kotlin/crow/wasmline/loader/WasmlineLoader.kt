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
 * Package workflows still resolve to local host-compatible artifacts.
 * Browser hosts consume raw `.wasm`, while Wasmtime-based hosts resolve to
 * precompiled `.cwasm` / `.pwasm`.
 *
 * 2026-04-08
 * @author crowforkotlin
 */
object DefaultWasmlineLoader : WasmlineLoader {
    override fun load(request: WasmlineLoadRequest): WasmlineLoadState {
        return loadSource(
            request = request,
            source = request.source,
            resolutionDepth = 0,
        )
    }

    private fun loadSource(
        request: WasmlineLoadRequest,
        source: WasmlineSource,
        resolutionDepth: Int,
    ): WasmlineLoadState {
        if (resolutionDepth > MAX_SOURCE_RESOLUTION_DEPTH) {
            return WasmlineLoadState.Failure(
                code = WasmlineLoadState.CODE_FAILURE,
                cause = "Wasmline load source resolution exceeded $MAX_SOURCE_RESOLUTION_DEPTH steps. Check resolver chaining for loops.",
            )
        }

        return when (source) {
            is WasmlineSource.LocalArtifactFile -> Wasmline.load(
                filepath = source.path,
                threadSafe = request.threadSafe,
            )

            is WasmlineSource.LocalPackageFile -> resolveSource(
                request = request,
                resolution = request.resolvers.localPackage?.resolve(source, request),
                description = "Local package source '${source.path}'",
                resolverHint = "request.resolvers.localPackage",
                resolutionDepth = resolutionDepth,
            )

            is WasmlineSource.RemotePackageUrl -> resolveSource(
                request = request,
                resolution = request.resolvers.remotePackage?.resolve(source, request),
                description = "Remote package source '${source.url}'",
                resolverHint = "request.resolvers.remotePackage",
                resolutionDepth = resolutionDepth,
            )
        }
    }

    private fun resolveSource(
        request: WasmlineLoadRequest,
        resolution: WasmlineSourceResolution?,
        description: String,
        resolverHint: String,
        resolutionDepth: Int,
    ): WasmlineLoadState {
        val resolved = resolution ?: return unsupportedSourceFailure(
            description = description,
            resolverHint = resolverHint,
        )
        return when (resolved) {
            is WasmlineSourceResolution.Complete -> resolved.state
            is WasmlineSourceResolution.ContinueWith -> loadSource(
                request = request,
                source = resolved.source,
                resolutionDepth = resolutionDepth + 1,
            )
        }
    }

    private fun unsupportedSourceFailure(
        description: String,
        resolverHint: String,
    ): WasmlineLoadState.Failure {
        return WasmlineLoadState.Failure(
            code = WasmlineLoadState.CODE_FAILURE,
            cause = "$description is not supported yet. Provide $resolverHint to resolve it into a local host-compatible artifact for the current runtime.",
        )
    }
}

private const val MAX_SOURCE_RESOLUTION_DEPTH = 8
