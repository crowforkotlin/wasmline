package crow.wasmline.plugin.core.aot

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineEngineKind
import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import crow.wasmline.plugin.core.compiler.WasmtimeCompiler

/**
 * Describes one normalized native target before it is expanded across profiles.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
data class WasmlineAotTargetSpec(
    val requestedTarget: String,
    val normalizedTarget: String,
    val artifactBackend: WasmlineEngineKind,
    val format: WasmlineArtifactFormat,
    val operatingSystem: String? = null,
    val architecture: String,
    val pointerWidth: Int,
    val cpuFeatureProfile: String? = null,
)

/**
 * Validates and normalizes configured Wasmtime targets for matrix expansion.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
object WasmlineArtifactTargetFactory {
    /** Returns a deterministic list of unique physical targets. */
    fun create(targets: Collection<String>): List<WasmlineAotTargetSpec> {
        val effectiveTargets = targets.ifEmpty { WasmtimeCompiler.defaultTargets }
        val results = effectiveTargets.map(::createTarget)
        val duplicateTargets = results.groupBy(WasmlineAotTargetSpec::normalizedTarget).filterValues { it.size > 1 }.keys
        require(duplicateTargets.isEmpty()) {
            "Duplicate AOT targets after normalization: ${duplicateTargets.sorted().joinToString()}."
        }
        return results.sortedWith(
            compareBy<WasmlineAotTargetSpec>(WasmlineAotTargetSpec::artifactBackend)
                .thenBy(WasmlineAotTargetSpec::normalizedTarget),
        )
    }

    private fun createTarget(target: String): WasmlineAotTargetSpec {
        val requested = target.trim()
        require(requested.matches(SAFE_TARGET)) {
            "AOT target may contain only letters, digits, dot, underscore and dash: '$target'."
        }
        val normalized = WasmtimeCompiler.normalizeTarget(requested)
        val (rawArchitecture, operatingSystem) = WasmtimeCompiler.parseTarget(normalized)
        if (rawArchitecture == "pulley32" || rawArchitecture == "pulley64") {
            val pointerWidth = if (rawArchitecture == "pulley32") 32 else 64
            return WasmlineAotTargetSpec(
                requestedTarget = requested,
                normalizedTarget = normalized,
                artifactBackend = WasmlineEngineKind.PULLEY,
                format = WasmlineArtifactFormat.PWASM,
                architecture = rawArchitecture,
                pointerWidth = pointerWidth,
            )
        }

        require(operatingSystem != null) { "Cranelift AOT target must identify an operating system: '$target'." }
        require(operatingSystem != "ios") {
            "iOS artifacts must use portable pulley64 PWASM instead of '$target'."
        }
        val architecture = normalizeArchitecture(rawArchitecture)
        val pointerWidth = pointerWidth(architecture)
        require(!(operatingSystem == "android" && pointerWidth == 32)) {
            "32-bit Android artifacts must use portable pulley32 PWASM instead of '$target'."
        }
        return WasmlineAotTargetSpec(
            requestedTarget = requested,
            normalizedTarget = normalized,
            artifactBackend = WasmlineEngineKind.CRANELIFT,
            format = WasmlineArtifactFormat.CWASM,
            operatingSystem = operatingSystem,
            architecture = architecture,
            pointerWidth = pointerWidth,
            cpuFeatureProfile = BASELINE_CPU_FEATURE_PROFILE,
        )
    }

    private fun normalizeArchitecture(value: String): String = when (value) {
        "amd64" -> "x86_64"
        "i386", "i586", "i686" -> "x86"
        "arm64" -> "aarch64"
        else -> value
    }.also { architecture ->
        require(architecture in SUPPORTED_ARCHITECTURES) { "Unsupported AOT target architecture '$architecture'." }
    }

    private fun pointerWidth(architecture: String): Int = when (architecture) {
        "x86" -> 32
        "aarch64", "x86_64", "riscv64" -> 64
        else -> error("Unsupported AOT target architecture '$architecture'.")
    }

    private const val BASELINE_CPU_FEATURE_PROFILE: String = "baseline-v1"
    private val SAFE_TARGET: Regex = Regex("[A-Za-z0-9._-]+")
    private val SUPPORTED_ARCHITECTURES: Set<String> = setOf("aarch64", "x86", "x86_64", "riscv64")
}
