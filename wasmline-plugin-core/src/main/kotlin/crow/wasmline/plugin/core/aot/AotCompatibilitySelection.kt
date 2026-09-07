package crow.wasmline.plugin.core.aot

import crow.wasmline.WasmlineEngineKind
import crow.wasmline.plugin.core.InternalWasmlineToolingApi

/**
 * Selects the Wasmline release range represented by a plugin's native AOT artifacts.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
sealed interface AotCompatibilitySelection {
    /**
     * Selects only the AOT generation used by the current Wasmline release.
     *
     * Date: 2026-08-29
     * Author: crowforkotlin
     */
    data object Current : AotCompatibilitySelection

    /**
     * Selects every generation from the effective minimum supported release.
     *
     * Date: 2026-08-29
     * Author: crowforkotlin
     */
    data object Minimum : AotCompatibilitySelection

    /**
     * Selects every generation published in the local release catalog.
     *
     * Date: 2026-08-29
     * Author: crowforkotlin
     */
    data object All : AotCompatibilitySelection

    /**
     * Selects the generations intersecting explicit closed Wasmline version ranges.
     *
     * Date: 2026-08-29
     * Author: crowforkotlin
     */
    data class VersionRanges(val ranges: List<WasmlineVersionRange>) : AotCompatibilitySelection {
        init {
            require(ranges.isNotEmpty()) { "versionRanges { } must include at least one range." }
        }
    }

    /** Returns the stable selector name used by diagnostics and reports. */
    fun diagnosticName(): String = when (this) {
        Current -> "current"
        Minimum -> "minimum"
        All -> "all"
        is VersionRanges -> "versionRanges"
    }
}

/**
 * Defines one closed Wasmline version interval used by a custom AOT selector.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
data class WasmlineVersionRange(val from: String, val through: String) {
    init {
        require(isStableWasmlineVersion(from)) { "AOT range from version must use x.y.z: '$from'." }
        require(isStableWasmlineVersion(through)) { "AOT range through version must use x.y.z: '$through'." }
        require(compareWasmlineVersions(from, through) <= 0) {
            "AOT range start '$from' must not exceed its end '$through'."
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
    val effectiveMinimumWasmlineVersion: String,
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
        manifestMinimumWasmlineVersion: String,
        releaseCatalog: WasmlineAotReleaseCatalog,
        profiles: Collection<AotCompatibilityProfileSpec>,
        requestedBackends: Set<WasmlineEngineKind>,
    ): AotCompatibilityResolution {
        require(isStableWasmlineVersion(manifestMinimumWasmlineVersion)) {
            "Manifest minSdkVersion must use x.y.z for AOT selection: '$manifestMinimumWasmlineVersion'."
        }
        val normalizedBackends = requestedBackends
            .distinct()
            .toSortedSet(compareBy(WasmlineEngineKind::name))
        require(normalizedBackends.isNotEmpty()) { "At least one AOT artifact backend is required." }
        releaseCatalog.validate(requireProfileBindings = true, profiles = profiles)
        val currentVersion = releaseCatalog.currentWasmlineVersion
        require(compareWasmlineVersions(manifestMinimumWasmlineVersion, currentVersion) <= 0) {
            "Manifest minSdkVersion $manifestMinimumWasmlineVersion exceeds the current Wasmline version $currentVersion."
        }
        val effectiveMinimum = maxWasmlineVersion(
            manifestMinimumWasmlineVersion,
            releaseCatalog.minimumSupportedWasmlineVersion,
        )
        val selectedRanges = when (selection) {
            AotCompatibilitySelection.Current -> listOf(releaseCatalog.ranges.last())

            AotCompatibilitySelection.Minimum -> releaseCatalog.rangesIntersecting(
                WasmlineVersionRange(effectiveMinimum, currentVersion),
            )

            AotCompatibilitySelection.All -> {
                val unusable = releaseCatalog.ranges.filterIndexed { index, item ->
                    val next = releaseCatalog.ranges.getOrNull(index + 1)?.fromWasmlineVersion
                    val endIsBeforeMinimum = next != null &&
                        compareWasmlineVersions(next, manifestMinimumWasmlineVersion) <= 0
                    val startsBeforeMinimum = compareWasmlineVersions(
                        item.fromWasmlineVersion,
                        manifestMinimumWasmlineVersion,
                    ) < 0
                    startsBeforeMinimum && endIsBeforeMinimum
                }
                require(unusable.isEmpty()) {
                    "all() selects AOT generations that are entirely below manifest.minSdkVersion " +
                        "$manifestMinimumWasmlineVersion. Use minimum() or versionRanges { ... }, or lower minSdkVersion."
                }
                releaseCatalog.ranges
            }

            is AotCompatibilitySelection.VersionRanges ->
                selection.ranges
                    .flatMap { range ->
                        require(releaseCatalog.intersectsCatalog(range)) {
                            "AOT version range ${range.from} through ${range.through} is outside the local Wasmline catalog."
                        }
                        releaseCatalog.rangesIntersecting(range)
                    }
                    .distinctBy(WasmlineAotReleaseRange::aotGeneration)
                    .sortedBy(WasmlineAotReleaseRange::aotGeneration)
        }
        check(selectedRanges.isNotEmpty()) { "AOT compatibility selection resolved to no release generation." }
        val generationsBelowManifestMinimum = selectedRanges.filter { range ->
            val catalogIndex = releaseCatalog.ranges.indexOfFirst { it.aotGeneration == range.aotGeneration }
            val nextStart = releaseCatalog.ranges.getOrNull(catalogIndex + 1)?.fromWasmlineVersion
            nextStart != null && compareWasmlineVersions(nextStart, manifestMinimumWasmlineVersion) <= 0
        }
        require(generationsBelowManifestMinimum.isEmpty()) {
            "AOT compatibility selection includes generations entirely below manifest.minSdkVersion " +
                "$manifestMinimumWasmlineVersion. Adjust minSdkVersion or the selected version ranges."
        }

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
            effectiveMinimumWasmlineVersion = effectiveMinimum,
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

/** Converts a stable task selector name to the shared selection model. */
@InternalWasmlineToolingApi
fun decodeAotCompatibilitySelection(kind: String, encodedRanges: Collection<String>): AotCompatibilitySelection = when (kind) {
    "current" -> AotCompatibilitySelection.Current.also { requireNoEncodedRanges(kind, encodedRanges) }
    "minimum" -> AotCompatibilitySelection.Minimum.also { requireNoEncodedRanges(kind, encodedRanges) }
    "all" -> AotCompatibilitySelection.All.also { requireNoEncodedRanges(kind, encodedRanges) }
    "versionRanges" -> AotCompatibilitySelection.VersionRanges(encodedRanges.map(::decodeWasmlineVersionRange))
    else -> error("Unknown AOT compatibility selector '$kind'.")
}

/** Rejects range task inputs paired with a selector that does not consume them. */
private fun requireNoEncodedRanges(kind: String, encodedRanges: Collection<String>) {
    require(encodedRanges.isEmpty()) {
        "AOT version ranges can only be used with the versionRanges selector, not '$kind'."
    }
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

private fun maxWasmlineVersion(left: String, right: String): String = if (compareWasmlineVersions(left, right) >= 0) left else right

private val STABLE_WASMLINE_VERSION: Regex = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")

internal val AOT_COMPATIBILITY_SELECTOR_NAMES: Set<String> =
    setOf("current", "minimum", "all", "versionRanges")
