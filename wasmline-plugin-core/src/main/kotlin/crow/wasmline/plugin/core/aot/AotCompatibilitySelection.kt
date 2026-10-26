package crow.wasmline.plugin.core.aot

import crow.wasmline.WasmlineEngineKind
import crow.wasmline.plugin.core.InternalWasmlineToolingApi

/**
 * Selects local AOT generations with an implicit current-release strategy.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
data class AotCompatibilitySelection(val strategies: List<AotGenerations> = emptyList()) {
    /** Returns the stable selector name used by diagnostics and reports. */
    fun diagnosticName(): String = AOT_COMPATIBILITY_SELECTOR
}

/**
 * Defines one closed interval of concrete stable Wasmline versions.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
data class WasmlineVersionRange(val from: String, val through: String) {
    init {
        require(isStableWasmlineVersion(from)) {
            "AOT range from version must use x.y.z: '$from'."
        }
        require(isStableWasmlineVersion(through)) {
            "AOT range through version must use x.y.z: '$through'."
        }
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
        currentWasmlineVersion: String = releaseCatalog.currentWasmlineVersion,
    ): AotCompatibilityResolution {
        val normalizedBackends = requestedBackends
            .toSortedSet(compareBy(WasmlineEngineKind::name))
        require(normalizedBackends.isNotEmpty()) { "At least one AOT artifact backend is required." }
        releaseCatalog.validate(requireProfileBindings = true, profiles = profiles)
        val currentGenerationIndex = generationIndex(currentWasmlineVersion, releaseCatalog)
        val selectedGenerations = sortedSetOf<Int>()

        fun includeWindow(anchor: Int, before: Int = 0, after: Int = 0) {
            require(before <= anchor && after <= releaseCatalog.ranges.lastIndex - anchor) {
                "AOT generation window before=$before after=$after around generation ${anchor + 1} " +
                    "is outside the local catalog generations 1..${releaseCatalog.ranges.size}."
            }
            for (index in (anchor - before)..(anchor + after)) {
                selectedGenerations.add(releaseCatalog.ranges[index].aotGeneration)
            }
        }

        fun includeStrategy(strategy: AotGenerations) {
            when (strategy) {
                AotGenerations.Current -> includeWindow(currentGenerationIndex)
                AotGenerations.Latest -> includeWindow(releaseCatalog.ranges.lastIndex)
                AotGenerations.Earliest -> includeWindow(0)
                AotGenerations.All -> releaseCatalog.ranges.forEach { range ->
                    selectedGenerations.add(range.aotGeneration)
                }
                is AotGenerations.Versions -> strategy.ranges.forEach { range ->
                    require(
                        compareWasmlineVersions(range.from, releaseCatalog.minimumSupportedWasmlineVersion) >= 0 &&
                            compareWasmlineVersions(range.through, releaseCatalog.currentWasmlineVersion) <= 0,
                    ) {
                        "AOT version range ${range.from} through ${range.through} is outside the local " +
                            "Wasmline catalog ${releaseCatalog.minimumSupportedWasmlineVersion}.." +
                            "${releaseCatalog.currentWasmlineVersion}."
                    }
                    releaseCatalog.rangesIntersecting(range).forEach { selected ->
                        selectedGenerations.add(selected.aotGeneration)
                    }
                }
                is AotGenerationWindow -> includeWindow(
                    anchor = when (strategy.anchor) {
                        AotGenerationAnchor.CURRENT -> currentGenerationIndex
                        AotGenerationAnchor.LATEST -> releaseCatalog.ranges.lastIndex
                        AotGenerationAnchor.EARLIEST -> 0
                    },
                    before = strategy.before,
                    after = strategy.after,
                )
            }
        }

        if (selection.strategies.isEmpty()) {
            includeWindow(currentGenerationIndex)
        } else {
            for (strategy in selection.strategies) includeStrategy(strategy)
        }
        check(selectedGenerations.isNotEmpty()) { "AOT compatibility selection resolved to no release generation." }

        val profilesById = profiles.associateBy(AotCompatibilityProfileSpec::id)
        val selectedProfiles = linkedMapOf<String, AotCompatibilityProfileSpec>()
        selectedGenerations.forEach { generation ->
            val range = releaseCatalog.ranges[generation - 1]
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
            selectedGenerations = selectedGenerations.toList(),
            profiles = selectedProfiles.values.sortedWith(AOT_PROFILE_ORDER),
            requestedBackends = normalizedBackends,
        )
    }

    private fun generationIndex(version: String, catalog: WasmlineAotReleaseCatalog): Int {
        require(isStableWasmlineVersion(version)) { "Current Wasmline version must use x.y.z: '$version'." }
        require(
            compareWasmlineVersions(version, catalog.minimumSupportedWasmlineVersion) >= 0 &&
                compareWasmlineVersions(version, catalog.currentWasmlineVersion) <= 0,
        ) {
            "Current Wasmline version '$version' is outside the local catalog " +
                "${catalog.minimumSupportedWasmlineVersion}..${catalog.currentWasmlineVersion}."
        }
        return catalog.ranges.indexOfLast { range ->
            compareWasmlineVersions(range.fromWasmlineVersion, version) <= 0
        }
    }
}

/** Encodes a typed strategy as a primitive Gradle task input. */
@InternalWasmlineToolingApi
fun AotGenerations.encodeForTaskInput(): String = when (this) {
    AotGenerations.Current -> "current"
    AotGenerations.Latest -> "latest"
    AotGenerations.Earliest -> "earliest"
    AotGenerations.All -> "all"
    is AotGenerations.Versions -> buildString {
        append("versions")
        ranges.forEach { range ->
            append('\u0000').append(range.from).append('\u0000').append(range.through)
        }
    }
    is AotGenerationWindow -> "window\u0000${anchor.token}\u0000$before\u0000$after"
}

/** Decodes a typed strategy before local selection and compiler resolution. */
@InternalWasmlineToolingApi
fun decodeAotGenerations(value: String): AotGenerations {
    val fields = value.split('\u0000')
    when (fields.first()) {
        "current", "latest", "earliest", "all" -> {
            require(fields.size == 1) { "Invalid encoded AOT generation strategy." }
            return when (value) {
                "current" -> AotGenerations.Current
                "latest" -> AotGenerations.Latest
                "earliest" -> AotGenerations.Earliest
                else -> AotGenerations.All
            }
        }
        "versions" -> {
            require(fields.size >= 3 && fields.size % 2 == 1) { "Invalid encoded AOT version selection." }
            val ranges = ArrayList<WasmlineVersionRange>((fields.size - 1) / 2)
            for (index in 1 until fields.size step 2) {
                ranges.add(WasmlineVersionRange(fields[index], fields[index + 1]))
            }
            return AotGenerations.Versions(ranges)
        }
        "window" -> {
            require(fields.size == 4) { "Invalid encoded AOT generation window." }
            val anchor = AotGenerationAnchor.entries.firstOrNull { it.token == fields[1] }
                ?: throw IllegalArgumentException("Invalid encoded AOT generation anchor.")
            val before = decodeGenerationCount(fields[2])
            val after = decodeGenerationCount(fields[3])
            return AotGenerationWindow(anchor, before, after)
        }
        else -> throw IllegalArgumentException("Invalid encoded AOT generation strategy.")
    }
}

private fun decodeGenerationCount(value: String): Int {
    require(value.isNotEmpty() && value.all { it in '0'..'9' }) { "AOT generation counts must be nonnegative integers." }
    return value.toIntOrNull() ?: throw IllegalArgumentException("AOT generation count exceeds Int.MAX_VALUE.")
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
