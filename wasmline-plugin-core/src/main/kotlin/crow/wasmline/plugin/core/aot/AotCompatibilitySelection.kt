package crow.wasmline.plugin.core.aot

import crow.wasmline.WasmlineEngineKind
import crow.wasmline.plugin.core.InternalWasmlineToolingApi

/**
 * Selects local AOT generations from closed Wasmline version ranges.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
data class AotCompatibilitySelection(val ranges: List<WasmlineVersionRange> = emptyList()) {
    /** Returns the stable selector name used by diagnostics and reports. */
    fun diagnosticName(): String = AOT_COMPATIBILITY_SELECTOR
}

/**
 * Defines one closed Wasmline version interval or the symbolic latest point.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
data class WasmlineVersionRange(val from: String, val through: String) {
    init {
        require(isStableWasmlineVersion(from) || (from == "latest" && through == "latest")) {
            "AOT range from version must use x.y.z, except for the latest point: '$from'."
        }
        require(isStableWasmlineVersion(through) || through == "latest") {
            "AOT range through version must use x.y.z or latest: '$through'."
        }
        if (from != "latest" && through != "latest") {
            require(compareWasmlineVersions(from, through) <= 0) {
                "AOT range start '$from' must not exceed its end '$through'."
            }
        }
    }
}

/**
 * Contains the deterministic result of resolving a release selector to AOT profiles.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
data class AotCompatibilityResolution(
    val selection: AotCompatibilitySelection,
    val selectedGenerations: List<Int>,
    val profiles: List<AotCompatibilityProfileSpec>,
    val requestedBackends: Set<WasmlineEngineKind>,
)

/**
 * Resolves Wasmline release selectors through immutable generation bindings.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
object AotCompatibilitySelectionResolver {
    /** Resolves one selector to stable, deduplicated backend profiles. */
    fun resolve(
        selection: AotCompatibilitySelection,
        releaseCatalog: WasmlineAotReleaseCatalog,
        profiles: Collection<AotCompatibilityProfileSpec>,
        requestedBackends: Set<WasmlineEngineKind>,
    ): AotCompatibilityResolution {
        val normalizedBackends = requestedBackends
            .distinct()
            .toSortedSet(compareBy(WasmlineEngineKind::name))
        require(normalizedBackends.isNotEmpty()) { "At least one AOT artifact backend is required." }
        releaseCatalog.validate(requireProfileBindings = true, profiles = profiles)
        val currentVersion = releaseCatalog.currentWasmlineVersion
        val minimumVersion = releaseCatalog.minimumSupportedWasmlineVersion
        val selectedRanges = if (selection.ranges.isEmpty()) {
            releaseCatalog.ranges
        } else {
            selection.ranges
                .flatMap { requestedRange ->
                    val range = if (requestedRange.through == "latest") {
                        WasmlineVersionRange(
                            from = if (requestedRange.from == "latest") currentVersion else requestedRange.from,
                            through = currentVersion,
                        )
                    } else {
                        requestedRange
                    }
                    require(
                        compareWasmlineVersions(range.from, minimumVersion) >= 0 &&
                            compareWasmlineVersions(range.through, currentVersion) <= 0,
                    ) {
                        "AOT version range ${range.from} through ${range.through} is outside the local " +
                            "Wasmline catalog $minimumVersion..$currentVersion."
                    }
                    releaseCatalog.rangesIntersecting(range)
                }
                .distinctBy(WasmlineAotReleaseRange::aotGeneration)
                .sortedBy(WasmlineAotReleaseRange::aotGeneration)
        }
        check(selectedRanges.isNotEmpty()) { "AOT compatibility selection resolved to no release generation." }

        val profilesById = profiles.associateBy(AotCompatibilityProfileSpec::id)
        val selectedProfiles = linkedMapOf<String, AotCompatibilityProfileSpec>()
        selectedRanges.forEach { range ->
            normalizedBackends.forEach { backend ->
                val profileId = range.profileIdsByBackend[backend]
                    ?: error("AOT generation ${range.aotGeneration} has no $backend profile binding.")
                val profile = profilesById[profileId]
                    ?: error("AOT generation ${range.aotGeneration} references unknown profile '$profileId'.")
                require(profile.artifactBackend == backend) {
                    "AOT generation ${range.aotGeneration} binds $backend to ${profile.artifactBackend}."
                }
                selectedProfiles[profile.id] = profile
            }
        }
        return AotCompatibilityResolution(
            selection = selection,
            selectedGenerations = selectedRanges.map(WasmlineAotReleaseRange::aotGeneration).distinct().sorted(),
            profiles = selectedProfiles.values.sortedWith(AOT_PROFILE_ORDER),
            requestedBackends = normalizedBackends,
        )
    }
}

/** Encodes one custom range for Gradle task inputs without JSON parsing. */
@InternalWasmlineToolingApi
fun WasmlineVersionRange.encodeForTaskInput(): String = "$from\u0000$through"

/** Decodes one custom range from a Gradle task input. */
@InternalWasmlineToolingApi
fun decodeWasmlineVersionRange(value: String): WasmlineVersionRange {
    val parts = value.split('\u0000')
    require(parts.size == 2) { "Invalid encoded Wasmline AOT version range." }
    return WasmlineVersionRange(from = parts[0], through = parts[1])
}

internal val AOT_PROFILE_ORDER: Comparator<AotCompatibilityProfileSpec> = compareBy(
    AotCompatibilityProfileSpec::wasmtimeDistributionVersion,
    { it.artifactBackend.name },
    AotCompatibilityProfileSpec::id,
)

internal fun isStableWasmlineVersion(value: String): Boolean = STABLE_WASMLINE_VERSION.matches(value)

internal fun compareWasmlineVersions(left: String, right: String): Int {
    require(isStableWasmlineVersion(left)) { "Invalid Wasmline version '$left'." }
    require(isStableWasmlineVersion(right)) { "Invalid Wasmline version '$right'." }
    val leftParts = left.split('.')
    val rightParts = right.split('.')
    leftParts.indices.forEach { index ->
        val leftPart = leftParts[index].trimStart('0').ifEmpty { "0" }
        val rightPart = rightParts[index].trimStart('0').ifEmpty { "0" }
        val lengthOrder = leftPart.length.compareTo(rightPart.length)
        if (lengthOrder != 0) return lengthOrder
        val lexicalOrder = leftPart.compareTo(rightPart)
        if (lexicalOrder != 0) return lexicalOrder
    }
    return 0
}

private val STABLE_WASMLINE_VERSION: Regex = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")

internal const val AOT_COMPATIBILITY_SELECTOR: String = "generations"
