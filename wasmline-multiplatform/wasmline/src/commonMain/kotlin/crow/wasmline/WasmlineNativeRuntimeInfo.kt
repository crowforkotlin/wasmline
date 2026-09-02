package crow.wasmline

/**
 * Reports immutable native runtime identity before serialized artifact selection.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 *
 * @property backend Linked native engine distribution.
 * @property supportedArtifactFormats Physical formats accepted by the linked engine.
 * @property wasmtimeVersion Human-readable upstream Wasmtime version.
 * @property aotCompatibilityProfileIdsByBackend Exact serialized-artifact identities by backend.
 * @property nativeBridgeAbiVersion Native C bridge ABI version.
 * @property wasmlineReleaseVersion Wasmline release that produced the native engine.
 * @property operatingSystem Canonical target operating system.
 * @property architecture Canonical target architecture.
 * @property pointerWidth Native pointer width in bits.
 * @property supportedCpuFeatureProfiles Frozen CWASM CPU feature policies.
 */
data class WasmlineNativeRuntimeInfo(
    val backend: WasmlineEngineKind,
    val supportedArtifactFormats: Set<WasmlineArtifactFormat>,
    val wasmtimeVersion: String,
    val aotCompatibilityProfileIdsByBackend: Map<WasmlineEngineKind, Set<String>>,
    val nativeBridgeAbiVersion: Int,
    val wasmlineReleaseVersion: String,
    val operatingSystem: String,
    val architecture: String,
    val pointerWidth: Int,
    val supportedCpuFeatureProfiles: Set<String>,
) {
    init {
        require(supportedArtifactFormats.isNotEmpty()) { "Native runtime must report a supported artifact format." }
        require(nativeBridgeAbiVersion > 0) { "Native bridge ABI version must be positive." }
        require(wasmlineReleaseVersion.isNotBlank()) { "Wasmline release version must not be blank." }
        require(operatingSystem.isNotBlank()) { "Native operatingSystem must not be blank." }
        require(architecture.isNotBlank()) { "Native architecture must not be blank." }
        require(pointerWidth == 32 || pointerWidth == 64) { "Native pointerWidth must be 32 or 64." }
        require(supportedCpuFeatureProfiles.none(String::isBlank)) {
            "Native CPU feature profile names must not be blank."
        }
    }

    /** Engines for which this runtime reports at least one exact AOT profile. */
    val supportedEngines: Set<WasmlineEngineKind>
        get() = aotCompatibilityProfileIdsByBackend.filterValues(Set<String>::isNotEmpty).keys
}
