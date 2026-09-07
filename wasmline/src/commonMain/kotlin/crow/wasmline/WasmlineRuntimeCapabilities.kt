package crow.wasmline

/**
 * Carries native bridge identity values used before serialized artifact loading.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
internal data class WasmlineRuntimeCapabilities(
    val backend: WasmlineEngineKind?,
    val supportedArtifactFormats: Set<WasmlineArtifactFormat>,
    val wasmtimeVersion: String,
    val aotCompatibilityProfileIdsByBackend: Map<WasmlineEngineKind, Set<String>>,
    val nativeBridgeAbiVersion: Int,
    val wasmlineReleaseVersion: String,
    val operatingSystem: String,
    val architecture: String,
    val pointerWidth: Int,
    val supportedCpuFeatureProfiles: Set<String>,
)

/** Converts bridge capabilities into the public immutable runtime identity. */
internal val WasmlineRuntimeCapabilities.nativeRuntimeInfo: WasmlineNativeRuntimeInfo?
    get() = backend?.let { nativeBackend ->
        WasmlineNativeRuntimeInfo(
            backend = nativeBackend,
            supportedArtifactFormats = supportedArtifactFormats,
            wasmtimeVersion = wasmtimeVersion,
            aotCompatibilityProfileIdsByBackend = aotCompatibilityProfileIdsByBackend,
            nativeBridgeAbiVersion = nativeBridgeAbiVersion,
            wasmlineReleaseVersion = wasmlineReleaseVersion,
            operatingSystem = operatingSystem,
            architecture = architecture,
            pointerWidth = pointerWidth,
            supportedCpuFeatureProfiles = supportedCpuFeatureProfiles,
        )
    }

/** Validates the linked native engine before any serialized artifact is loaded. */
internal fun WasmlineRuntimeCapabilities.validatedNativeIdentity(): WasmlineRuntimeCapabilities {
    val selectedBackend = requireNotNull(backend) { "Native Wasmline runtime must report its engine backend." }
    check(nativeBridgeAbiVersion == WasmlineReleaseIdentity.NATIVE_BRIDGE_ABI_VERSION) {
        "Native Wasmline bridge ABI $nativeBridgeAbiVersion does not match Kotlin bridge ABI " +
            "${WasmlineReleaseIdentity.NATIVE_BRIDGE_ABI_VERSION}."
    }
    check(wasmlineReleaseVersion == WasmlineReleaseIdentity.RELEASE_VERSION) {
        "Native Wasmline release $wasmlineReleaseVersion does not match Kotlin release " +
            "${WasmlineReleaseIdentity.RELEASE_VERSION}."
    }
    check(supportedArtifactFormats.isNotEmpty()) { "Native Wasmline runtime reported no supported AOT artifact formats." }
    check(WasmlineArtifactFormat.RAW_WASM !in supportedArtifactFormats) {
        "Native Wasmline runtime must not report RAW_WASM as an AOT format."
    }
    check(wasmtimeVersion.matches(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$"))) {
        "Native Wasmline runtime reported an invalid Wasmtime version."
    }
    check(aotCompatibilityProfileIdsByBackend.isNotEmpty()) {
        "Native Wasmline runtime reported no AOT compatibility profile."
    }
    check(aotCompatibilityProfileIdsByBackend.values.none(Set<String>::isEmpty)) {
        "Native Wasmline runtime reported an empty AOT compatibility profile set."
    }
    check(
        aotCompatibilityProfileIdsByBackend.values
            .asSequence()
            .flatten()
            .all(AOT_COMPATIBILITY_PROFILE_ID_PATTERN::matches),
    ) {
        "Native Wasmline runtime reported an invalid AOT compatibility profile ID."
    }
    check(operatingSystem.isNotBlank() && architecture.isNotBlank()) {
        "Native Wasmline runtime reported an incomplete host target."
    }
    check(pointerWidth == 32 || pointerWidth == 64) {
        "Native Wasmline runtime pointer width must be 32 or 64."
    }

    val supportsCwasm = WasmlineArtifactFormat.CWASM in supportedArtifactFormats
    val supportsPwasm = WasmlineArtifactFormat.PWASM in supportedArtifactFormats
    check(!supportsCwasm || aotCompatibilityProfileIdsByBackend[WasmlineEngineKind.CRANELIFT].orEmpty().isNotEmpty()) {
        "Native Wasmline runtime reports CWASM without a Cranelift compatibility profile."
    }
    check(!supportsPwasm || aotCompatibilityProfileIdsByBackend[WasmlineEngineKind.PULLEY].orEmpty().isNotEmpty()) {
        "Native Wasmline runtime reports PWASM without a Pulley compatibility profile."
    }
    check(supportsCwasm || WasmlineEngineKind.CRANELIFT !in aotCompatibilityProfileIdsByBackend) {
        "Native Wasmline runtime reports a Cranelift profile without CWASM capability."
    }
    check(supportsPwasm || WasmlineEngineKind.PULLEY !in aotCompatibilityProfileIdsByBackend) {
        "Native Wasmline runtime reports a Pulley profile without PWASM capability."
    }
    when (selectedBackend) {
        WasmlineEngineKind.CRANELIFT -> check(supportsCwasm) {
            "Cranelift native runtime must report CWASM capability."
        }

        WasmlineEngineKind.PULLEY -> check(supportsPwasm && !supportsCwasm) {
            "Pulley native runtime must report PWASM-only capability."
        }
    }
    check(supportedCpuFeatureProfiles.none(String::isBlank)) {
        "Native Wasmline runtime reported a blank CPU feature profile."
    }
    check(!supportsCwasm || supportedCpuFeatureProfiles.isNotEmpty()) {
        "CWASM-capable native runtime must report a CPU feature profile."
    }
    check(supportsCwasm || supportedCpuFeatureProfiles.isEmpty()) {
        "PWASM-only native runtime must not report CWASM CPU feature profiles."
    }
    return this
}

/** Validates a descriptor against exact native runtime identity. */
internal fun WasmlineArtifactDescriptor.runtimeCompatibilityError(runtime: WasmlineRuntimeCapabilities): String? {
    val format = artifactFormat ?: return null
    if (format == WasmlineArtifactFormat.RAW_WASM) return null
    if (format !in runtime.supportedArtifactFormats) {
        return "The native runtime does not support artifact format $format."
    }

    val requiredBackend = when (format) {
        WasmlineArtifactFormat.RAW_WASM -> return null
        WasmlineArtifactFormat.CWASM -> WasmlineEngineKind.CRANELIFT
        WasmlineArtifactFormat.PWASM -> WasmlineEngineKind.PULLEY
    }
    val profileId = aotCompatibilityProfileId
        ?: return "AOT artifacts require aotCompatibilityProfileId metadata."
    if (profileId !in runtime.aotCompatibilityProfileIdsByBackend[requiredBackend].orEmpty()) {
        return "$format profile '$profileId' is not supported by the linked $requiredBackend runtime."
    }

    return when (format) {
        WasmlineArtifactFormat.RAW_WASM -> null
        WasmlineArtifactFormat.CWASM -> cwasmCompatibilityError(runtime)
        WasmlineArtifactFormat.PWASM -> pwasmCompatibilityError(runtime)
    }
}

private fun WasmlineArtifactDescriptor.cwasmCompatibilityError(runtime: WasmlineRuntimeCapabilities): String? {
    if (operatingSystem != runtime.operatingSystem ||
        architecture != runtime.architecture ||
        pointerWidth != runtime.pointerWidth
    ) {
        return "CWASM target $operatingSystem/$architecture/$pointerWidth does not match native runtime " +
            "${runtime.operatingSystem}/${runtime.architecture}/${runtime.pointerWidth}."
    }
    val featureProfile = cpuFeatureProfile ?: return "CWASM requires cpuFeatureProfile metadata."
    if (featureProfile !in runtime.supportedCpuFeatureProfiles) {
        return "CWASM CPU feature profile '$featureProfile' is not supported by the native runtime."
    }
    return null
}

private fun WasmlineArtifactDescriptor.pwasmCompatibilityError(runtime: WasmlineRuntimeCapabilities): String? {
    val expectedArchitecture = "pulley${runtime.pointerWidth}"
    if (operatingSystem != null) return "PWASM operatingSystem must be absent."
    if (architecture != expectedArchitecture || pointerWidth != runtime.pointerWidth) {
        return "PWASM target $architecture/$pointerWidth does not match native runtime pointer width ${runtime.pointerWidth}."
    }
    if (cpuFeatureProfile != null) return "PWASM cpuFeatureProfile must be absent."
    return null
}

private val AOT_COMPATIBILITY_PROFILE_ID_PATTERN: Regex = Regex("^sha256:[0-9a-f]{64}$")
