@file:OptIn(crow.wasmline.InternalWasmlineRuntimeApi::class)

package crow.wasmline.loader

import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineLoadStage
import crow.wasmline.WasmlineLoadState
import crow.wasmline.WasmlineLog
import crow.wasmline.internal.runtime.WasmlineRuntimeBridge
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.loader.internal.WasmlineLocalPackageResolution
import crow.wasmline.loader.internal.WasmlineRemotePackageResolution
import crow.wasmline.loader.internal.structuredLoadFailure

/**
 * Internal loader implementation that resolves sources through the resolver chain.
 *
 * Handles local artifacts, local packages, and remote packages with optional
 * network client support. Called by [WasmlineLoader].
 */
internal object DefaultWasmlineLoader {
    private const val P = "[DefaultWasmlineLoader]"

    suspend fun load(request: WasmlineLoadRequest): WasmlineLoadState {
        WasmlineLog.logger?.info("$P Loading from source: ${request.source}")
        return loadSource(
            request = request,
            source = request.source,
            resolutionDepth = 0,
        )
    }

    private suspend fun loadSource(request: WasmlineLoadRequest, source: WasmlineSource, resolutionDepth: Int): WasmlineLoadState {
        if (resolutionDepth > MAX_SOURCE_RESOLUTION_DEPTH) {
            WasmlineLog.logger?.error("$P Source resolution exceeded max depth ($MAX_SOURCE_RESOLUTION_DEPTH)")
            return structuredLoadFailure(
                stage = WasmlineLoadStage.SOURCE_RESOLUTION,
                code = WasmlineErrorCode.SOURCE_RESOLUTION_FAILED,
                message = "Wasmline load source resolution exceeded $MAX_SOURCE_RESOLUTION_DEPTH steps. Check resolver chaining for loops.",
            )
        }

        return when (source) {
            is WasmlineSource.LocalArtifactPath -> {
                val descriptor = source.descriptor ?: WasmlineArtifactDescriptor(path = source.path)
                loadLocalArtifact(descriptor, request)
            }

            is VerifiedPackageArtifact -> loadLocalArtifact(source.descriptor, request)

            is WasmlineSource.LocalManifestPath -> {
                val resolution = request.resolvers.localPackage?.resolve(source, request)
                    ?: WasmlineLocalPackageResolution.resolve(source, request)
                resolveSource(
                    request = request,
                    resolution = resolution,
                    resolutionDepth = resolutionDepth,
                )
            }

            is WasmlineSource.RemoteManifestUrl -> {
                WasmlineLog.logger?.debug("$P Resolving remote source: ${source.url}")
                val customResolution = request.resolvers.remotePackage?.resolve(source, request)
                if (customResolution != null) {
                    resolveSource(
                        request = request,
                        resolution = customResolution,
                        resolutionDepth = resolutionDepth,
                    )
                } else {
                    val builtInResolution = WasmlineRemotePackageResolution.resolve(source, request)
                    resolveSource(
                        request = request,
                        resolution = builtInResolution,
                        resolutionDepth = resolutionDepth,
                    )
                }
            }
        }
    }

    private fun loadLocalArtifact(descriptor: WasmlineArtifactDescriptor, request: WasmlineLoadRequest): WasmlineLoadState {
        val validationError = descriptor.validationError()
        if (validationError != null) {
            return structuredLoadFailure(
                stage = WasmlineLoadStage.ARTIFACT_VALIDATION,
                code = WasmlineErrorCode.ARTIFACT_DESCRIPTOR_INVALID,
                message = "Invalid artifact descriptor: $validationError",
            )
        }

        return WasmlineRuntimeBridge.loadResolvedArtifact(descriptor = descriptor, config = request.options.runtimeConfig)
    }

    private suspend fun resolveSource(
        request: WasmlineLoadRequest,
        resolution: WasmlineSourceResolution,
        resolutionDepth: Int,
    ): WasmlineLoadState = when (resolution) {
        is WasmlineSourceResolution.Complete -> resolution.state

        is WasmlineSourceResolution.ContinueWith -> loadSource(
            request = request,
            source = resolution.source,
            resolutionDepth = resolutionDepth + 1,
        )
    }
}

private const val MAX_SOURCE_RESOLUTION_DEPTH = 8
