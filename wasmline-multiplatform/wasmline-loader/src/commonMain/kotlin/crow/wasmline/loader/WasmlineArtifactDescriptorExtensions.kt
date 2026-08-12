/**
 * Converts manifest artifact metadata to a runtime descriptor.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
package crow.wasmline.loader

import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineComponentRpcContract
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineArtifactType

fun WasmlineArtifact.toDescriptor(path: String): WasmlineArtifactDescriptor = WasmlineArtifactDescriptor(
    path = path,
    artifactFormat = when (type) {
        WasmlineArtifactType.WASM,
        WasmlineArtifactType.COMPONENT_WASM,
        -> WasmlineArtifactFormat.RAW_WASM

        WasmlineArtifactType.CWASM -> WasmlineArtifactFormat.CWASM

        WasmlineArtifactType.PWASM -> WasmlineArtifactFormat.PWASM
    },
    targetCpu = targetCpu,
    targetOs = targetOs,
    targetCompilerVersion = targetCompilerVersion,
    is64Bit = is64Bit,
    executionModel = executionModel,
    invocationProtocol = normalizedInvocationProtocol(),
    exportName = normalizedExportName(),
    contractMetadata = contractMetadata,
)

/** Recognizes the explicit metadata written by pre-split Component RPC manifests. */
internal fun WasmlineArtifact.isLegacyComponentRpcManifestArtifact(): Boolean = executionModel == WasmlineExecutionModel.COMPONENT_MODEL &&
    invocationProtocol == WasmlineInvocationProtocol.COMPONENT_EXPORT &&
    exportName == WasmlineComponentRpcContract.DEFAULT_EXPORT &&
    contractMetadata[WasmlineComponentRpcContract.METADATA_WIT_PACKAGE] == WasmlineComponentRpcContract.WIT_PACKAGE &&
    contractMetadata[WasmlineComponentRpcContract.METADATA_PROFILE] == WasmlineComponentRpcContract.PROFILE &&
    !contractMetadata[WasmlineComponentRpcContract.METADATA_CODEC].isNullOrBlank() &&
    !contractMetadata[WasmlineComponentRpcContract.METADATA_VERSION].isNullOrBlank()

private fun WasmlineArtifact.normalizedInvocationProtocol(): WasmlineInvocationProtocol = if (isLegacyComponentRpcManifestArtifact()) {
    WasmlineInvocationProtocol.WASMLINE_COMPONENT_RPC
} else {
    invocationProtocol
}

private fun WasmlineArtifact.normalizedExportName(): String? = when (normalizedInvocationProtocol()) {
    WasmlineInvocationProtocol.WASMLINE_COMPONENT_RPC -> WasmlineComponentRpcContract.DEFAULT_EXPORT
    else -> exportName
}
