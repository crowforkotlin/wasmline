/**
 * Converts manifest artifact metadata to a runtime descriptor.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
package crow.wasmline.loader

import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.loader.model.WasmlineArtifact

fun WasmlineArtifact.toDescriptor(path: String): WasmlineArtifactDescriptor = WasmlineArtifactDescriptor(
    path = path,
    executionModel = executionModel,
    invocationProtocol = invocationProtocol,
    exportName = exportName,
    contractMetadata = contractMetadata,
)
