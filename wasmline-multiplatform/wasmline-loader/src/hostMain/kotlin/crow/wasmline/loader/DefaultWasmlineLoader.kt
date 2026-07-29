package crow.wasmline.loader

import crow.wasmline.WasmlineLoadState
import crow.wasmline.WasmlineLog
import crow.wasmline.loader.internal.WasmlineRemotePackageResolution
import crow.wasmline.wasmlineLoadArtifact

/**
 * Internal loader implementation that resolves sources through the resolver chain.
 *
 * Handles local artifacts, local packages, and remote packages with optional
 * network client support. Called by [WasmlineLoader].
 */
internal object DefaultWasmlineLoader {
    private const val P = "[DefaultWasmlineLoader]"

    fun load(request: WasmlineLoadRequest): WasmlineLoadState {
        WasmlineLog.logger?.info("$P Loading from source: ${request.source}")
        return loadSource(
            request = request,
            source = request.source,
            resolutionDepth = 0,
        )
    }

    private fun loadSource(request: WasmlineLoadRequest, source: WasmlineSource, resolutionDepth: Int): WasmlineLoadState {
        if (resolutionDepth > MAX_SOURCE_RESOLUTION_DEPTH) {
            WasmlineLog.logger?.error("$P Source resolution exceeded max depth ($MAX_SOURCE_RESOLUTION_DEPTH)")
            return WasmlineLoadState.Failure(
                code = WasmlineLoadState.CODE_FAILURE,
                cause = "Wasmline load source resolution exceeded $MAX_SOURCE_RESOLUTION_DEPTH steps. Check resolver chaining for loops.",
            )
        }

        return when (source) {
            is WasmlineSource.LocalArtifactPath -> wasmlineLoadArtifact(
                filepath = source.path,
                config = request.config,
            )

            is WasmlineSource.LocalManifestPath -> resolveSource(
                request = request,
                resolution = request.resolvers.localPackage?.resolve(source, request),
                description = "Local package source '${source.path}'",
                resolverHint = "request.resolvers.localPackage",
                resolutionDepth = resolutionDepth,
            )

            is WasmlineSource.RemoteManifestUrl -> {
                WasmlineLog.logger?.debug("$P Resolving remote source: ${source.url}")
                val customResolution = request.resolvers.remotePackage?.resolve(source, request)
                if (customResolution != null) {
                    resolveSource(
                        request = request,
                        resolution = customResolution,
                        description = "Remote package source '${source.url}'",
                        resolverHint = "request.resolvers.remotePackage",
                        resolutionDepth = resolutionDepth,
                    )
                } else if (request.config.networkClient != null) {
                    val builtInResolution = WasmlineRemotePackageResolution.resolve(source, request)
                    resolveSource(
                        request = request,
                        resolution = builtInResolution,
                        description = "Remote package source '${source.url}'",
                        resolverHint = "request.config.networkClient",
                        resolutionDepth = resolutionDepth,
                    )
                } else {
                    unsupportedSourceFailure(
                        description = "Remote package source '${source.url}'",
                        resolverHint = "request.resolvers.remotePackage or request.config.networkClient",
                    )
                }
            }
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

    private fun unsupportedSourceFailure(description: String, resolverHint: String): WasmlineLoadState.Failure = WasmlineLoadState.Failure(
        code = WasmlineLoadState.CODE_FAILURE,
        cause = "$description is not supported yet. Provide $resolverHint to " +
            "resolve it into a local host-compatible artifact for the current runtime.",
    )
}

private const val MAX_SOURCE_RESOLUTION_DEPTH = 8
