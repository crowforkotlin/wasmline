package crow.wasmline.cli

import crow.wasmline.WasmlineEngineKind
import crow.wasmline.plugin.core.aot.AotCompatibilityCatalog
import crow.wasmline.plugin.core.aot.AotCompatibilityProfileSpec
import crow.wasmline.plugin.core.aot.AotCompatibilityResolution
import crow.wasmline.plugin.core.aot.AotCompatibilitySelectionResolver
import crow.wasmline.plugin.core.aot.WasmlineAotReleaseCatalog
import crow.wasmline.plugin.core.aot.WasmlineAotReleaseRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies the typed CLI AOT generation grammar and its local catalog resolution.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
class CliAotSelectionTest {
    @Test
    fun omittedGenerationTokensSelectTheCurrentGeneration() {
        val resolution = resolve(emptyList())

        assertEquals(localGenerations().takeLast(1), resolution.selectedGenerations)
        assertEquals(resolution.selectedGenerations, resolve(listOf("current")).selectedGenerations)
        assertEquals(localPulleyProfileIds().takeLast(1).toSet(), resolution.profiles.map { it.id }.toSet())
    }

    @Test
    fun namedStrategyTokensSelectTheirAnchoredGenerations() {
        assertEquals(localGenerations().takeLast(1), resolve(listOf("current")).selectedGenerations)
        assertEquals(localGenerations().takeLast(1), resolve(listOf("latest")).selectedGenerations)
        assertEquals(localGenerations().take(1), resolve(listOf("earliest")).selectedGenerations)
        assertEquals(localGenerations(), resolve(listOf("all")).selectedGenerations)
    }

    @Test
    fun concreteVersionTokensSelectTheirContainingGeneration() {
        val catalog = AotCompatibilityCatalog.releaseCatalog()
        val firstVersion = catalog.ranges.first().fromWasmlineVersion
        val currentVersion = catalog.currentWasmlineVersion
        val zeroPaddedVersion = firstVersion.split('.').joinToString(".") { segment -> "0$segment" }
        val longZeroSegmentVersion = firstVersion.substringBeforeLast('.') +
            ".${"0".repeat(64)}" + firstVersion.substringAfterLast('.')

        assertEquals(localGenerations().take(1), resolve(listOf(firstVersion)).selectedGenerations)
        assertEquals(localGenerations().take(1), resolve(listOf("$firstVersion..$firstVersion")).selectedGenerations)
        assertEquals(localGenerations().takeLast(1), resolve(listOf(currentVersion)).selectedGenerations)
        assertEquals(localGenerations().take(1), resolve(listOf(zeroPaddedVersion)).selectedGenerations)
        assertEquals(localGenerations().take(1), resolve(listOf(longZeroSegmentVersion)).selectedGenerations)
    }

    @Test
    fun closedConcreteRangesAndRepeatedTokensUnionDeduplicatedGenerations() {
        val catalog = AotCompatibilityCatalog.releaseCatalog()
        val minimumVersion = catalog.minimumSupportedWasmlineVersion
        val currentVersion = catalog.currentWasmlineVersion
        val firstVersion = catalog.ranges.first().fromWasmlineVersion

        val resolution = resolve(
            listOf(minimumVersion, "$minimumVersion..$currentVersion", currentVersion, "$firstVersion..$firstVersion"),
        )

        assertEquals(localGenerations(), resolution.selectedGenerations)
        assertEquals(
            localPulleyProfileIds().toSet(),
            resolution.profiles.map { profile -> profile.id }.toSet(),
        )
        assertEquals(
            resolution.profiles.size,
            resolution.profiles.map { profile -> profile.id }.distinct().size,
        )
        assertEquals(setOf(WasmlineEngineKind.PULLEY), resolution.requestedBackends)
    }

    @Test
    fun generationWindowsIncludeTheAnchorAndRequestedNeighbors() {
        // Windows only make sense across several generations, so this case uses a
        // synthetic catalog instead of depending on how many generations the
        // shipped release happens to contain.
        val (catalog, profiles) = syntheticWindowCatalog()
        val generations = catalog.ranges.map { it.aotGeneration }

        assertEquals(generations.takeLast(2), resolve(catalog, profiles, listOf("current:before=1")).selectedGenerations)
        assertEquals(
            generations.takeLast(2),
            resolve(catalog, profiles, listOf("current:before=1:after=0")).selectedGenerations,
        )
        assertEquals(
            generations.takeLast(2),
            resolve(catalog, profiles, listOf("current:after=0:before=1")).selectedGenerations,
        )
        assertEquals(generations.takeLast(2), resolve(catalog, profiles, listOf("latest:before=1")).selectedGenerations)
        assertEquals(generations.take(2), resolve(catalog, profiles, listOf("earliest:after=1")).selectedGenerations)
        assertEquals(generations.takeLast(1), resolve(catalog, profiles, listOf("current:before=0:after=0")).selectedGenerations)
        assertEquals(
            generations,
            resolve(
                catalog,
                profiles,
                listOf("earliest:after=${generations.lastIndex}", "current:before=1"),
            ).selectedGenerations,
        )
    }

    @Test
    fun unavailableGenerationWindowsFailWithoutClipping() {
        val generations = localGenerations()

        for (token in listOf(
            "current:after=${generations.size}",
            "current:before=0:after=${generations.size}",
            "latest:before=${generations.size}",
            "earliest:after=${generations.size}",
        )) {
            assertFailsWith<IllegalArgumentException>(token) {
                resolve(listOf(token))
            }
        }
    }

    @Test
    fun pointsAndRangesOutsideTheLocalCatalogFailWithoutClipping() {
        val catalog = AotCompatibilityCatalog.releaseCatalog()
        val minimumVersion = catalog.minimumSupportedWasmlineVersion
        val currentVersion = catalog.currentWasmlineVersion
        val belowMinimum = predecessorAotVersion(minimumVersion)
        val aboveCurrent = successorAotVersion(currentVersion)

        for (token in listOf(
            belowMinimum,
            "$belowMinimum..$minimumVersion",
            "$belowMinimum..$currentVersion",
            aboveCurrent,
            "$minimumVersion..$aboveCurrent",
            "$currentVersion..$aboveCurrent",
        )) {
            assertFailsWith<IllegalArgumentException>(token) {
                resolve(listOf(token))
            }
        }
    }

    @Test
    fun rejectsMalformedGenerationTokens() {
        for (token in listOf(
            "",
            " ",
            " 1.0.0",
            "1.0.0 ",
            "1.0",
            "1.0.0.0",
            "v1.0.0",
            "LATEST",
            "Latest",
            "Current",
            "ALL",
            "1.0.0,1.0.1",
            "1.0.0 .. 1.0.1",
            "1.0.1..1.0.0",
            "..1.0.1",
            "1.0.1..",
            "1.0.0..1.0.1 ",
            "1.0.1..1.0.0..1.0.2",
            "latest..latest",
            "latest..1.0.1",
            "1.0.1..latest",
            "minimum",
            "versionRanges",
            "current:",
            "current:before",
            "current:before=",
            "current:=1",
            "current:before=-1",
            "current:before=+1",
            "current:before=01",
            "current:before=2147483648",
            "current:before=1:before=2",
            "current:before=1:after=2:before=0",
            "current:before=1:after=2:after=3",
            "current:before=1:after=01",
            "current:during=1",
            "current:before=1 ",
            " current:before=1",
            "current::before=1",
            "current:before=1=2",
            "latest:after=1",
            "latest:after=0",
            "earliest:before=1",
            "earliest:before=0",
            "all:before=1",
            "1.0.0:before=1",
        )) {
            assertFailsWith<IllegalArgumentException>(token) {
                parseCliAotSelection(listOf(token))
            }
        }
    }

    private fun resolve(tokens: Collection<String>): AotCompatibilityResolution =
        resolveCliAotSelection(parseCliAotSelection(tokens), listOf("pulley64"))

    private fun resolve(
        catalog: WasmlineAotReleaseCatalog,
        profiles: List<AotCompatibilityProfileSpec>,
        tokens: Collection<String>,
    ): AotCompatibilityResolution = AotCompatibilitySelectionResolver.resolve(
        selection = parseCliAotSelection(tokens),
        releaseCatalog = catalog,
        profiles = profiles,
        requestedBackends = setOf(WasmlineEngineKind.PULLEY),
        currentWasmlineVersion = catalog.currentWasmlineVersion,
    )

    private fun localGenerations(): List<Int> = AotCompatibilityCatalog.releaseCatalog().ranges.map { range -> range.aotGeneration }

    private fun localPulleyProfileIds(): List<String> = AotCompatibilityCatalog.releaseCatalog().ranges.mapNotNull { range ->
        range.profileIdsByBackend[WasmlineEngineKind.PULLEY]
    }.distinct()

    /**
     * Builds a three-generation catalog so anchored windows have real neighbors.
     *
     * The first generation must mark every backend as changed, so both backends are
     * bound per generation; the tested windows only resolve the PULLEY profile.
     */
    private fun syntheticWindowCatalog(): Pair<WasmlineAotReleaseCatalog, List<AotCompatibilityProfileSpec>> {
        val profilePairs = (1..3).map { generation ->
            WasmlineEngineKind.entries.sortedBy(WasmlineEngineKind::name).associateWith { backend ->
                AotCompatibilityProfileSpec(
                    id = "pending",
                    artifactBackend = backend,
                    wasmtimeVersion = "50.$generation.0",
                    wasmtimeDistributionVersion = "50.$generation.0.$generation",
                    wasmtimeSourceRevision = generation.toString().repeat(40),
                    serializedArtifactFormatIdentity = "fixture-format-v1",
                    compileProfileSchemaVersion = 1,
                    engineConfigurationProfile = "fixture-${backend.name.lowercase()}",
                    introducedInWasmlineVersion = "1.0.$generation",
                ).let { profile -> profile.copy(id = AotCompatibilityCatalog.calculateCompatibilityId(profile)) }
            }
        }
        val profiles = profilePairs.flatMap { it.values }.sortedBy { it.id }
        val catalog = WasmlineAotReleaseCatalog(
            schemaVersion = 1,
            currentWasmlineVersion = "1.0.3",
            minimumSupportedWasmlineVersion = "1.0.1",
            ranges = profilePairs.mapIndexed { index, bindings ->
                val pulley = bindings.getValue(WasmlineEngineKind.PULLEY)
                WasmlineAotReleaseRange(
                    fromWasmlineVersion = "1.0.${index + 1}",
                    aotGeneration = index + 1,
                    wasmtimeDistributionVersion = pulley.wasmtimeDistributionVersion,
                    changedBackends = WasmlineEngineKind.entries.sortedBy(WasmlineEngineKind::name),
                    profileIdsByBackend = bindings.mapValues { (_, profile) -> profile.id },
                )
            },
        )
        return catalog to profiles
    }
}

/** Returns the stable version immediately below one local catalog version. */
internal fun predecessorAotVersion(version: String): String = shiftAotVersion(version, -1)

/** Returns the stable version immediately above one local catalog version. */
internal fun successorAotVersion(version: String): String = shiftAotVersion(version, 1)

private fun shiftAotVersion(version: String, delta: Int): String {
    val parts = version.split('.').map { segment -> segment.toLong() }
    val shiftedPatch = parts[2] + delta
    if (shiftedPatch in 0L..9L) return "${parts[0]}.${parts[1]}.$shiftedPatch"
    return if (delta < 0) {
        require(parts[1] > 0L || parts[0] > 0L) { "Cannot shift '$version' below zero." }
        if (parts[1] > 0L) "${parts[0]}.${parts[1] - 1}.9" else "${parts[0] - 1}.9.9"
    } else {
        "${parts[0]}.${parts[1]}.$shiftedPatch"
    }
}
