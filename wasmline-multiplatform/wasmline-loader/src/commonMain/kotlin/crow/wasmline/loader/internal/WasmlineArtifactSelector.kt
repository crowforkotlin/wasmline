package crow.wasmline.loader.internal

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineEngineKind
import crow.wasmline.loader.model.WasmlineArtifactTarget
import crow.wasmline.loader.model.WasmlineArtifactVariant
import crow.wasmline.loader.model.WasmlineManifest

/**
 * Represents the deterministic result of manifest artifact selection.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
internal sealed interface WasmlineArtifactSelection {
    /**
     * Contains the unique target, variant, and matched backend profile.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    data class Selected(
        val target: WasmlineArtifactTarget,
        val variant: WasmlineArtifactVariant,
        val matchedAotCompatibilityProfileId: String?,
    ) : WasmlineArtifactSelection

    /**
     * Reports that the valid manifest has no artifact compatible with the host.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    data object NotCompatible : WasmlineArtifactSelection

    /**
     * Reports an ambiguous manifest selection result.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    data class Invalid(val cause: String) : WasmlineArtifactSelection
}

/**
 * Selects one artifact in linear time using exact host and backend identities.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
internal object WasmlineArtifactSelector {
    /** Selects the unique highest-priority artifact without filesystem or network access. */
    fun select(manifest: WasmlineManifest, host: WasmlineHostArtifactTarget): WasmlineArtifactSelection {
        if (host.operatingSystem == "browser") return selectRawWasm(manifest, host)
        val runtime = host.nativeRuntimeInfo ?: return WasmlineArtifactSelection.NotCompatible

        if (WasmlineArtifactFormat.CWASM in host.supportedArtifactFormats) {
            when (
                val cwasm = selectAot(
                    manifest = manifest,
                    host = host,
                    format = WasmlineArtifactFormat.CWASM,
                    profileIds = runtime.aotCompatibilityProfileIdsByBackend[WasmlineEngineKind.CRANELIFT].orEmpty(),
                )
            ) {
                is WasmlineArtifactSelection.Selected,
                is WasmlineArtifactSelection.Invalid,
                -> return cwasm

                WasmlineArtifactSelection.NotCompatible -> Unit
            }
        }

        if (WasmlineArtifactFormat.PWASM !in host.supportedArtifactFormats) {
            return WasmlineArtifactSelection.NotCompatible
        }
        return selectAot(
            manifest = manifest,
            host = host,
            format = WasmlineArtifactFormat.PWASM,
            profileIds = runtime.aotCompatibilityProfileIdsByBackend[WasmlineEngineKind.PULLEY].orEmpty(),
        )
    }

    private fun selectRawWasm(manifest: WasmlineManifest, host: WasmlineHostArtifactTarget): WasmlineArtifactSelection {
        if (WasmlineArtifactFormat.RAW_WASM !in host.supportedArtifactFormats) {
            return WasmlineArtifactSelection.NotCompatible
        }
        val candidates = manifest.artifactTargets.mapNotNull { target ->
            if (target.format != WasmlineArtifactFormat.RAW_WASM ||
                target.architecture != "wasm32" ||
                target.pointerWidth != 32
            ) {
                return@mapNotNull null
            }
            target.variants.singleOrNull()?.let { variant ->
                WasmlineArtifactSelection.Selected(target, variant, matchedAotCompatibilityProfileId = null)
            }
        }
        return uniqueCandidate(candidates, WasmlineArtifactFormat.RAW_WASM)
    }

    private fun selectAot(
        manifest: WasmlineManifest,
        host: WasmlineHostArtifactTarget,
        format: WasmlineArtifactFormat,
        profileIds: Set<String>,
    ): WasmlineArtifactSelection {
        if (profileIds.isEmpty()) return WasmlineArtifactSelection.NotCompatible
        val candidates = mutableListOf<WasmlineArtifactSelection.Selected>()
        manifest.artifactTargets.forEach { target ->
            if (!target.matchesHost(format, host)) return@forEach
            target.variants.forEach { variant ->
                val matchedIds = variant.aotCompatibilityProfileIds.filterTo(mutableSetOf()) { it in profileIds }
                if (matchedIds.isNotEmpty()) {
                    candidates += WasmlineArtifactSelection.Selected(
                        target = target,
                        variant = variant,
                        matchedAotCompatibilityProfileId = matchedIds.minOrNull(),
                    )
                }
            }
        }
        return uniqueCandidate(candidates, format)
    }

    private fun WasmlineArtifactTarget.matchesHost(requiredFormat: WasmlineArtifactFormat, host: WasmlineHostArtifactTarget): Boolean {
        if (format != requiredFormat || pointerWidth != host.pointerWidth) return false
        return when (format) {
            WasmlineArtifactFormat.RAW_WASM -> false

            WasmlineArtifactFormat.CWASM ->
                operatingSystem == host.operatingSystem &&
                    architecture == host.architecture &&
                    cpuFeatureProfile in host.nativeRuntimeInfo?.supportedCpuFeatureProfiles.orEmpty()

            WasmlineArtifactFormat.PWASM ->
                operatingSystem == null &&
                    architecture == "pulley${host.pointerWidth}"
        }
    }

    private fun uniqueCandidate(
        candidates: List<WasmlineArtifactSelection.Selected>,
        format: WasmlineArtifactFormat,
    ): WasmlineArtifactSelection = when (candidates.size) {
        0 -> WasmlineArtifactSelection.NotCompatible

        1 -> candidates.single()

        else -> WasmlineArtifactSelection.Invalid(
            "Manifest contains ${candidates.size} equally compatible $format artifact variants.",
        )
    }
}
