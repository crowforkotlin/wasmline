package crow.wasmline

/** Describes the linked native runtime used to validate serialized Wasmtime artifacts. */
internal data class WasmlineRuntimeCapabilities(
    val wasmtimeVersion: String,
    val supportsCranelift: Boolean,
    val supportsPulley: Boolean,
    val targetOs: String,
    val targetCpu: String,
    val is64Bit: Boolean,
)

/**
 * Maps linked capabilities to the immutable engine-variant policy used before
 * artifact selection. Cranelift distributions also contain Pulley support, so
 * compiler availability deliberately identifies the Cranelift variant first.
 */
internal val WasmlineRuntimeCapabilities.nativeBackendPolicy: WasmlineNativeBackend?
    get() = when {
        supportsCranelift -> WasmlineNativeBackend.CRANELIFT
        supportsPulley -> WasmlineNativeBackend.PULLEY
        else -> null
    }

internal val WasmlineRuntimeCapabilities.nativeRuntimeInfo: WasmlineNativeRuntimeInfo?
    get() = nativeBackendPolicy?.let { backend ->
        WasmlineNativeRuntimeInfo(
            backend = backend,
            wasmtimeVersion = wasmtimeVersion,
            targetOs = targetOs,
            targetCpu = targetCpu,
            is64Bit = is64Bit,
        )
    }

internal fun WasmlineArtifactDescriptor.runtimeCompatibilityError(runtime: WasmlineRuntimeCapabilities): String? {
    val format = artifactFormat ?: return null
    if (format == WasmlineArtifactFormat.RAW_WASM) return null

    val artifactVersion = targetCompilerVersion
        ?.let(wasmtimeCompilerVersionPattern::matchEntire)
        ?.groupValues
        ?.get(1)
        ?: return "AOT artifact targetCompilerVersion must use 'wasmtime-x.y.z'."
    if (!wasmtimeVersionPattern.matches(runtime.wasmtimeVersion)) {
        return "Native runtime reported an invalid Wasmtime version '${runtime.wasmtimeVersion}'."
    }
    if (artifactVersion != runtime.wasmtimeVersion) {
        return "AOT artifact requires Wasmtime $artifactVersion, but the native runtime is ${runtime.wasmtimeVersion}."
    }

    return when (format) {
        WasmlineArtifactFormat.RAW_WASM -> null
        WasmlineArtifactFormat.CWASM -> cwasmCompatibilityError(runtime)
        WasmlineArtifactFormat.PWASM -> pwasmCompatibilityError(runtime)
    }
}

private fun WasmlineArtifactDescriptor.cwasmCompatibilityError(runtime: WasmlineRuntimeCapabilities): String? {
    if (!runtime.supportsCranelift) return "CWASM requires a Cranelift-capable native runtime."

    val artifactOs = normalizeArtifactOs(targetOs)
        ?: return "CWASM requires targetOs metadata."
    val artifactCpu = normalizeArtifactCpu(targetCpu)
        ?: return "CWASM requires targetCpu metadata."
    val artifactBitness = is64Bit
        ?: return "CWASM requires bitness metadata."
    val runtimeOs = normalizeArtifactOs(runtime.targetOs) ?: runtime.targetOs.lowercase()
    val runtimeCpu = normalizeArtifactCpu(runtime.targetCpu) ?: runtime.targetCpu.lowercase()

    if (artifactOs != runtimeOs || artifactCpu != runtimeCpu || artifactBitness != runtime.is64Bit) {
        return "CWASM target $artifactOs/$artifactCpu/${bitness(artifactBitness)} does not match native runtime " +
            "$runtimeOs/$runtimeCpu/${bitness(runtime.is64Bit)}."
    }
    return null
}

private fun WasmlineArtifactDescriptor.pwasmCompatibilityError(runtime: WasmlineRuntimeCapabilities): String? {
    if (!runtime.supportsPulley) return "PWASM requires a Pulley-capable native runtime."

    val artifactCpu = targetCpu?.lowercase()
        ?: return "PWASM requires pulley32 or pulley64 targetCpu metadata."
    val expectedBitness = when (artifactCpu) {
        "pulley32" -> false
        "pulley64" -> true
        else -> return "PWASM targetCpu must be pulley32 or pulley64."
    }
    val artifactBitness = is64Bit
        ?: return "PWASM requires bitness metadata."
    val artifactOs = targetOs?.lowercase()
    if (artifactOs != null && artifactOs != "pulley") {
        return "PWASM targetOs must be absent; legacy 'pulley' is also accepted."
    }
    if (artifactBitness != expectedBitness || artifactBitness != runtime.is64Bit) {
        return "PWASM ${bitness(artifactBitness)} target does not match native runtime ${bitness(runtime.is64Bit)}."
    }
    return null
}

private fun normalizeArtifactOs(value: String?): String? = when (value?.lowercase()) {
    null -> null
    "mac", "macos", "darwin", "osx" -> "macos"
    "win", "windows" -> "windows"
    "linux" -> "linux"
    "android" -> "android"
    "ios" -> "ios"
    else -> value.lowercase()
}

private fun normalizeArtifactCpu(value: String?): String? = when (value?.lowercase()) {
    null -> null
    "amd64", "x86_64" -> "x86_64"
    "arm64", "aarch64" -> "aarch64"
    else -> value.lowercase()
}

private fun bitness(is64Bit: Boolean): String = if (is64Bit) "64-bit" else "32-bit"

private val wasmtimeCompilerVersionPattern = Regex("^wasmtime-(\\d+\\.\\d+\\.\\d+)$")
private val wasmtimeVersionPattern = Regex("^\\d+\\.\\d+\\.\\d+$")
