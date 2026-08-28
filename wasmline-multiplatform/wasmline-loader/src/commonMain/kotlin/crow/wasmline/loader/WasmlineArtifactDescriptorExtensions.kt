package crow.wasmline.loader

import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.loader.internal.WasmlineArtifactSelection
import crow.wasmline.loader.model.WasmlineRuntimeContract

/** Converts a selected manifest target and contract to a runtime descriptor. */
internal fun WasmlineArtifactSelection.Selected.toDescriptor(path: String, contract: WasmlineRuntimeContract): WasmlineArtifactDescriptor =
    WasmlineArtifactDescriptor(
        path = path,
        artifactFormat = target.format,
        operatingSystem = target.operatingSystem,
        architecture = target.architecture,
        pointerWidth = target.pointerWidth,
        cpuFeatureProfile = target.cpuFeatureProfile,
        aotCompatibilityProfileId = matchedAotCompatibilityProfileId,
        executionModel = contract.executionModel,
        invocationProtocol = contract.invocationProtocol,
        exportName = contract.exportName,
        contractMetadata = contract.contractMetadata,
        rawAbi = contract.rawAbi,
    )
