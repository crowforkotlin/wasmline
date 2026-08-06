/**
 * Converts manifest artifact metadata to a runtime descriptor.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
package crow.wasmline.loader

import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineArtifactFormat
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
    invocationProtocol = invocationProtocol,
    exportName = exportName,
    contractMetadata = contractMetadata,
)
