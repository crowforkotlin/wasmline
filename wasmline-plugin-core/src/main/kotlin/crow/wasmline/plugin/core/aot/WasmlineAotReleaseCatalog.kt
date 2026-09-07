package crow.wasmline.plugin.core.aot

import crow.wasmline.WasmlineEngineKind
import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Maps stable Wasmline release ranges to sequential native AOT generations.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
@Serializable
@InternalWasmlineToolingApi
data class WasmlineAotReleaseCatalog(
    val schemaVersion: Int,
    val currentWasmlineVersion: String,
    val minimumSupportedWasmlineVersion: String,
    val ranges: List<WasmlineAotReleaseRange>,
) {
    /** Validates the public range structure and optional detailed profile bindings. */
    fun validate(requireProfileBindings: Boolean = false, profiles: Collection<AotCompatibilityProfileSpec> = emptyList()) {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported Wasmline AOT release catalog schema $schemaVersion."
        }
        require(isStableWasmlineVersion(currentWasmlineVersion)) {
            "AOT catalog currentWasmlineVersion must use x.y.z."
        }
        require(isStableWasmlineVersion(minimumSupportedWasmlineVersion)) {
            "AOT catalog minimumSupportedWasmlineVersion must use x.y.z."
        }
        require(compareWasmlineVersions(minimumSupportedWasmlineVersion, currentWasmlineVersion) <= 0) {
            "AOT catalog minimum supported version exceeds its current version."
        }
        require(ranges.isNotEmpty()) { "AOT release catalog ranges must not be empty." }
        val profilesById = profiles.associateBy(AotCompatibilityProfileSpec::id)
        var previous: WasmlineAotReleaseRange? = null
        ranges.forEachIndexed { index, range ->
            require(isStableWasmlineVersion(range.fromWasmlineVersion)) {
                "AOT generation ${range.aotGeneration} has an invalid range start."
            }
            require(range.aotGeneration == index + 1) {
                "AOT generations must start at 1 and increase without gaps."
            }
            previous?.let { prior ->
                require(compareWasmlineVersions(prior.fromWasmlineVersion, range.fromWasmlineVersion) < 0) {
                    "AOT release range start versions must be strictly increasing."
                }
            }
            require(WASMTIME_DISTRIBUTION_VERSION.matches(range.wasmtimeDistributionVersion)) {
                "AOT generation ${range.aotGeneration} has an invalid Wasmtime distribution version."
            }
            require(range.changedBackends.isNotEmpty()) {
                "AOT generation ${range.aotGeneration} must identify changed backends."
            }
            require(range.changedBackends.size == range.changedBackends.distinct().size) {
                "AOT generation ${range.aotGeneration} contains duplicate changed backends."
            }
            require(range.changedBackends == range.changedBackends.sortedBy(WasmlineEngineKind::name)) {
                "AOT generation ${range.aotGeneration} changed backends must use canonical order."
            }
            if (index == 0) {
                require(range.changedBackends.toSet() == WasmlineEngineKind.entries.toSet()) {
                    "The first AOT generation must mark every backend as changed."
                }
            }
            if (requireProfileBindings) {
                validateBindings(range, previous, profilesById)
            } else {
                require(range.profileIdsByBackend.isEmpty()) {
                    "The public AOT release catalog must not expose profile bindings."
                }
            }
            previous = range
        }
        require(compareWasmlineVersions(ranges.first().fromWasmlineVersion, minimumSupportedWasmlineVersion) <= 0) {
            "The first AOT release range starts after the minimum supported version."
        }
        require(compareWasmlineVersions(ranges.last().fromWasmlineVersion, currentWasmlineVersion) <= 0) {
            "The current Wasmline version is outside the final AOT release range."
        }
    }

    /** Returns a copy that contains only fields present in the public catalog. */
    fun withoutProfileBindings(): WasmlineAotReleaseCatalog = copy(
        ranges = ranges.map { range -> range.copy(profileIdsByBackend = emptyMap()) },
    )

    /** Returns catalog generations intersecting one closed version range. */
    fun rangesIntersecting(range: WasmlineVersionRange): List<WasmlineAotReleaseRange> {
        if (!intersectsCatalog(range)) return emptyList()
        return ranges.filterIndexed { index, item ->
            val nextStart = ranges.getOrNull(index + 1)?.fromWasmlineVersion
            compareWasmlineVersions(range.through, item.fromWasmlineVersion) >= 0 &&
                (nextStart == null || compareWasmlineVersions(range.from, nextStart) < 0)
        }
    }

    /** Returns whether a closed version range intersects the catalog. */
    fun intersectsCatalog(range: WasmlineVersionRange): Boolean =
        compareWasmlineVersions(range.through, ranges.first().fromWasmlineVersion) >= 0 &&
            compareWasmlineVersions(range.from, currentWasmlineVersion) <= 0

    private fun validateBindings(
        range: WasmlineAotReleaseRange,
        previous: WasmlineAotReleaseRange?,
        profilesById: Map<String, AotCompatibilityProfileSpec>,
    ) {
        require(range.profileIdsByBackend.keys == WasmlineEngineKind.entries.toSet()) {
            "AOT generation ${range.aotGeneration} must bind every backend exactly once."
        }
        val distributions = range.profileIdsByBackend.map { (backend, profileId) ->
            require(AOT_PROFILE_ID_PATTERN.matches(profileId)) {
                "AOT generation ${range.aotGeneration} has an invalid profile ID for $backend."
            }
            val profile = profilesById[profileId]
                ?: error("AOT generation ${range.aotGeneration} references unknown profile '$profileId'.")
            require(profile.artifactBackend == backend) {
                "AOT generation ${range.aotGeneration} binds $backend to ${profile.artifactBackend}."
            }
            require(profile.wasmtimeDistributionVersion == range.wasmtimeDistributionVersion) {
                "AOT generation ${range.aotGeneration} profile distribution does not match its release range."
            }
            profile.wasmtimeDistributionVersion
        }.toSet()
        require(distributions == setOf(range.wasmtimeDistributionVersion)) {
            "AOT generation ${range.aotGeneration} profile distribution does not match its release range."
        }
        val actualChanges = WasmlineEngineKind.entries.filter { backend ->
            previous == null || previous.profileIdsByBackend[backend] != range.profileIdsByBackend[backend]
        }.toSet()
        require(actualChanges == range.changedBackends.toSet()) {
            "AOT generation ${range.aotGeneration} changedBackends does not match its profile bindings."
        }
    }

    /**
     * Defines the public catalog resource and schema.
     *
     * Date: 2026-08-29
     * Author: crowforkotlin
     */
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        const val RESOURCE_PATH: String = "META-INF/wasmline/aot/aot-compatibility.json"
    }
}

/**
 * Defines one release switch point and its optional internal profile bindings.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
@Serializable
@InternalWasmlineToolingApi
data class WasmlineAotReleaseRange(
    val fromWasmlineVersion: String,
    val aotGeneration: Int,
    val wasmtimeDistributionVersion: String,
    val changedBackends: List<WasmlineEngineKind>,
    val profileIdsByBackend: Map<WasmlineEngineKind, String> = emptyMap(),
)

/**
 * Parses and validates public AOT compatibility catalogs from local or remote resources.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
object WasmlineAotReleaseCatalogCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = false
        prettyPrint = true
    }

    /** Decodes one public catalog and rejects internal profile bindings. */
    fun decodePublic(value: String): WasmlineAotReleaseCatalog = json.decodeFromString<WasmlineAotReleaseCatalog>(value).also { catalog ->
        catalog.validate(requireProfileBindings = false)
    }

    /** Encodes one public catalog using deterministic formatting. */
    fun encodePublic(value: WasmlineAotReleaseCatalog): String {
        val publicValue = value.withoutProfileBindings()
        publicValue.validate(requireProfileBindings = false)
        return json.encodeToString(WasmlineAotReleaseCatalog.serializer(), publicValue) + "\n"
    }
}

private val WASMTIME_DISTRIBUTION_VERSION: Regex = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+\\.[1-9][0-9]*$")
private val AOT_PROFILE_ID_PATTERN: Regex = Regex("^sha256:[0-9a-f]{64}$")
