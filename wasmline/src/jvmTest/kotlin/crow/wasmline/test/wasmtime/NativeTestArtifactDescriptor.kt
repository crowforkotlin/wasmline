package crow.wasmline.test.wasmtime

import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineEngineKind
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.WasmlineRuntimeCapabilities

/** Creates a native test descriptor from the runtime's exact immutable AOT identity. */
internal fun nativeTestArtifactDescriptor(
    path: String,
    artifactFormat: WasmlineArtifactFormat,
    runtime: WasmlineRuntimeCapabilities,
    executionModel: WasmlineExecutionModel,
    invocationProtocol: WasmlineInvocationProtocol,
    exportName: String? = null,
    contractMetadata: Map<String, String> = emptyMap(),
): WasmlineArtifactDescriptor {
    val backend = when (artifactFormat) {
        WasmlineArtifactFormat.CWASM -> WasmlineEngineKind.CRANELIFT
        WasmlineArtifactFormat.PWASM -> WasmlineEngineKind.PULLEY
        WasmlineArtifactFormat.RAW_WASM -> error("Native AOT test descriptors cannot use RAW_WASM.")
    }
    val profileId = runtime.aotCompatibilityProfileIdsByBackend[backend]
        ?.singleOrNull()
        ?: error("Native runtime must report exactly one $backend AOT compatibility profile for test fixtures.")
    val cranelift = artifactFormat == WasmlineArtifactFormat.CWASM
    val cpuFeatureProfile = if (cranelift) {
        runtime.supportedCpuFeatureProfiles.singleOrNull()
            ?: error("Native runtime must report exactly one CPU feature profile for CWASM test fixtures.")
    } else {
        null
    }
    return WasmlineArtifactDescriptor(
        path = path,
        artifactFormat = artifactFormat,
        operatingSystem = runtime.operatingSystem.takeIf { cranelift },
        architecture = if (cranelift) runtime.architecture else "pulley${runtime.pointerWidth}",
        pointerWidth = runtime.pointerWidth,
        cpuFeatureProfile = cpuFeatureProfile,
        aotCompatibilityProfileId = profileId,
        executionModel = executionModel,
        invocationProtocol = invocationProtocol,
        exportName = exportName,
        contractMetadata = contractMetadata,
    )
}
