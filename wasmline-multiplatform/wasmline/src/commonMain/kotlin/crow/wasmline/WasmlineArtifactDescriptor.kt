package crow.wasmline

import kotlinx.serialization.Serializable

/**
 * Describes a binary artifact and its invocation boundary.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 *
 * @property path Artifact path or Web cache key.
 * @property artifactFormat Physical binary format, when known.
 * @property operatingSystem Canonical native operating-system target.
 * @property architecture Canonical target architecture.
 * @property pointerWidth Target pointer width in bits.
 * @property cpuFeatureProfile Deterministic CPU feature policy for CWASM.
 * @property aotCompatibilityProfileId Backend-specific serialized artifact identity.
 * @property executionModel Runtime execution model.
 * @property invocationProtocol Host invocation protocol.
 * @property exportName Optional selected export name for protocol adapters.
 * @property contractMetadata Additional contract metadata.
 * @property rawAbi Optional versioned scalar Core Wasm ABI metadata used by RAW_EXPORT.
 */
@Serializable
data class WasmlineArtifactDescriptor(
    val path: String,
    val artifactFormat: WasmlineArtifactFormat? = null,
    val operatingSystem: String? = null,
    val architecture: String? = null,
    val pointerWidth: Int? = null,
    val cpuFeatureProfile: String? = null,
    val aotCompatibilityProfileId: String? = null,
    val executionModel: WasmlineExecutionModel = WasmlineExecutionModel.CORE_WASM,
    val invocationProtocol: WasmlineInvocationProtocol = WasmlineInvocationProtocol.WASMLINE_SERVICE,
    val exportName: String? = null,
    val contractMetadata: Map<String, String> = emptyMap(),
    val rawAbi: RawAbiMetadata? = null,
) {
    /** Validates the descriptor against the execution model and invocation protocol. */
    fun validationError(): String? {
        if (path.isBlank()) return "Artifact path must not be blank."
        if (pointerWidth != null && pointerWidth !in setOf(32, 64)) return "Artifact pointerWidth must be 32 or 64."
        if (operatingSystem != null && operatingSystem.isBlank()) return "Artifact operatingSystem must not be blank."
        if (architecture != null && architecture.isBlank()) return "Artifact architecture must not be blank."
        if (cpuFeatureProfile != null && cpuFeatureProfile.isBlank()) return "Artifact cpuFeatureProfile must not be blank."
        if (aotCompatibilityProfileId != null && !AOT_PROFILE_ID_PATTERN.matches(aotCompatibilityProfileId)) {
            return "Artifact aotCompatibilityProfileId must use sha256:<lowercase-digest>."
        }
        if (exportName != null && exportName.isBlank()) return "Artifact exportName must not be blank when present."
        if (rawAbi != null && invocationProtocol != WasmlineInvocationProtocol.RAW_EXPORT) {
            return "rawAbi metadata requires the RAW_EXPORT invocation protocol."
        }
        if (rawAbi != null && rawAbi.version > RawAbiMetadata.CURRENT_VERSION) {
            return "Unsupported rawAbi metadata version ${rawAbi.version}."
        }
        if (
            executionModel == WasmlineExecutionModel.COMPONENT_MODEL &&
            invocationProtocol == WasmlineInvocationProtocol.WASMLINE_SERVICE &&
            exportName != null &&
            exportName != WasmlineComponentServiceContract.DEFAULT_EXPORT
        ) {
            return "WASMLINE_SERVICE Component exportName must be '${WasmlineComponentServiceContract.DEFAULT_EXPORT}'."
        }
        val contractError = when (executionModel) {
            WasmlineExecutionModel.CORE_WASM -> when (invocationProtocol) {
                WasmlineInvocationProtocol.WASMLINE_SERVICE,
                WasmlineInvocationProtocol.RAW_EXPORT,
                -> null

                WasmlineInvocationProtocol.COMPONENT_EXPORT ->
                    "COMPONENT_EXPORT requires COMPONENT_MODEL."
            }

            WasmlineExecutionModel.COMPONENT_MODEL -> when (invocationProtocol) {
                WasmlineInvocationProtocol.COMPONENT_EXPORT,
                WasmlineInvocationProtocol.WASMLINE_SERVICE,
                -> null

                WasmlineInvocationProtocol.RAW_EXPORT ->
                    "COMPONENT_MODEL cannot use RAW_EXPORT."
            }
        }
        if (contractError != null) return contractError
        return when (artifactFormat) {
            null -> null

            WasmlineArtifactFormat.RAW_WASM -> if (aotCompatibilityProfileId == null) {
                null
            } else {
                "RAW_WASM artifacts must not declare an AOT compatibility profile."
            }

            WasmlineArtifactFormat.CWASM,
            WasmlineArtifactFormat.PWASM,
            -> if (aotCompatibilityProfileId != null) {
                null
            } else {
                "AOT artifacts require aotCompatibilityProfileId metadata."
            }
        }
    }

    /**
     * Defines the canonical AOT compatibility profile ID syntax.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    private companion object {
        val AOT_PROFILE_ID_PATTERN: Regex = Regex("^sha256:[0-9a-f]{64}$")
    }
}
