package crow.wasmline.plugin.core.aot

import crow.wasmline.WasmlineEngineKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Verifies generation selection, strict local bounds, and backend-specific AOT identities.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
class AotCompatibilityCatalogTest {
    @Test
    fun emptySelectionUsesEveryLocalGeneration() {
        val (catalog, profiles) = fixtureCatalog()

        val resolution = resolve(catalog, profiles, AotCompatibilitySelection())

        assertEquals(listOf(1, 2, 3), resolution.selectedGenerations)
        assertEquals(5, resolution.profiles.size)
        assertEquals(resolution.profiles.map { it.id }.distinct().size, resolution.profiles.size)
        assertEquals(
            setOf(WasmlineEngineKind.CRANELIFT, WasmlineEngineKind.PULLEY),
            resolution.requestedBackends,
        )
    }

    @Test
    fun concretePointSelectsExactlyItsGeneration() {
        val (catalog, profiles) = fixtureCatalog()

        val resolution = resolve(
            catalog,
            profiles,
            AotCompatibilitySelection(listOf(WasmlineVersionRange("1.0.0", "1.0.0"))),
        )

        assertEquals(listOf(1), resolution.selectedGenerations)
        assertEquals(
            catalog.ranges.first().profileIdsByBackend.values.toSet(),
            resolution.profiles.map { it.id }.toSet(),
        )
    }

    @Test
    fun latestPointSelectsOnlyTheCurrentGeneration() {
        val (catalog, profiles) = fixtureCatalog()

        val resolution = resolve(
            catalog,
            profiles,
            AotCompatibilitySelection(listOf(WasmlineVersionRange("latest", "latest"))),
            setOf(WasmlineEngineKind.PULLEY),
        )

        assertEquals(listOf(3), resolution.selectedGenerations)
        assertEquals(
            listOf(catalog.ranges.last().profileIdsByBackend.getValue(WasmlineEngineKind.PULLEY)),
            resolution.profiles.map { it.id },
        )
    }

    @Test
    fun latestThroughExtendsAConcreteStartToTheCurrentGeneration() {
        val (catalog, profiles) = fixtureCatalog()

        val resolution = resolve(
            catalog,
            profiles,
            AotCompatibilitySelection(listOf(WasmlineVersionRange("1.0.0", "latest"))),
        )

        assertEquals(listOf(1, 2, 3), resolution.selectedGenerations)
    }

    @Test
    fun closedIntervalIncludesItsThroughBoundaryGeneration() {
        val (catalog, profiles) = fixtureCatalog()

        val beforeSwitch = resolve(
            catalog,
            profiles,
            AotCompatibilitySelection(listOf(WasmlineVersionRange("1.0.0", "1.4.9"))),
        )
        val atSwitch = resolve(
            catalog,
            profiles,
            AotCompatibilitySelection(listOf(WasmlineVersionRange("1.0.0", "1.5.0"))),
        )

        assertEquals(listOf(1), beforeSwitch.selectedGenerations)
        assertEquals(listOf(1, 2), atSwitch.selectedGenerations)
        assertEquals(
            setOf(WasmlineEngineKind.CRANELIFT, WasmlineEngineKind.PULLEY),
            atSwitch.requestedBackends,
        )
    }

    @Test
    fun overlappingPointsAndRangesMergeGenerationsAndReuseProfiles() {
        val (catalog, profiles) = fixtureCatalog()
        val selection = AotCompatibilitySelection(
            listOf(
                WasmlineVersionRange("1.0.0", "1.0.0"),
                WasmlineVersionRange("1.2.3", "1.2.3"),
                WasmlineVersionRange("1.5.0", "2.5.0"),
                WasmlineVersionRange("2.0.0", "3.0.0"),
            ),
        )

        val resolution = resolve(catalog, profiles, selection)
        val repeatedGeneration = resolve(
            catalog,
            profiles,
            AotCompatibilitySelection(
                listOf(
                    WasmlineVersionRange("1.0.0", "1.0.0"),
                    WasmlineVersionRange("1.2.3", "1.2.3"),
                ),
            ),
        )

        val expectedProfileIds = catalog.ranges
            .flatMap { range -> range.profileIdsByBackend.values }
            .toSet()
        val craneliftBindings = catalog.ranges.map { it.profileIdsByBackend.getValue(WasmlineEngineKind.CRANELIFT) }
        assertEquals(craneliftBindings[1], craneliftBindings[2])
        assertEquals(1, resolution.profiles.count { it.id == craneliftBindings[1] })
        assertEquals(listOf(1), repeatedGeneration.selectedGenerations)
        assertEquals(2, repeatedGeneration.profiles.size)
        assertEquals(listOf(1, 2, 3), resolution.selectedGenerations)
        assertEquals(expectedProfileIds, resolution.profiles.map { it.id }.toSet())
        assertEquals(5, resolution.profiles.size)
        assertEquals(expectedProfileIds.size, resolution.profiles.size)
    }

    @Test
    fun selectionBoundsMustStayInsideTheLocalCatalog() {
        val (catalog, profiles) = fixtureCatalog()

        listOf(
            WasmlineVersionRange("0.9.9", "0.9.9"),
            WasmlineVersionRange("0.9.9", "1.0.0"),
            WasmlineVersionRange("0.5.0", "1.5.0"),
            WasmlineVersionRange("1.0.0", "3.0.1"),
            WasmlineVersionRange("2.5.0", "4.0.0"),
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                resolve(catalog, profiles, AotCompatibilitySelection(listOf(invalid)))
            }
        }
    }

    @Test
    fun invertedRangesAndSymbolicRangeStartsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            WasmlineVersionRange("2.0.0", "1.0.0")
        }
        assertFailsWith<IllegalArgumentException> {
            WasmlineVersionRange("latest", "2.0.0")
        }
    }

    @Test
    fun versionsCompareNumericallyWithoutLeadingZeroOrLengthLimits() {
        assertEquals(0, compareWasmlineVersions("01.0.0", "1.0.0"))
        assertEquals(0, compareWasmlineVersions("1.0000000000000000000000000000002.0", "1.2.0"))
        assertTrue(compareWasmlineVersions("1.9999999999999999999999999999999.0", "1.2.0") > 0)
        val (catalog, profiles) = fixtureCatalog()

        val resolution = resolve(
            catalog,
            profiles,
            AotCompatibilitySelection(listOf(WasmlineVersionRange("01.5.0", "02.5.1"))),
        )

        assertEquals(listOf(2, 3), resolution.selectedGenerations)
    }

    @Test
    fun backendProfilesHaveDifferentCompatibilityIdentities() {
        val (catalog, profiles) = fixtureCatalog()
        val pulley = profiles.first { it.artifactBackend == WasmlineEngineKind.PULLEY }
        val cranelift = profiles.first { it.artifactBackend == WasmlineEngineKind.CRANELIFT }

        assertNotEquals(pulley.id, cranelift.id)
        assertEquals(catalog.ranges.first().profileIdsByBackend[WasmlineEngineKind.PULLEY], pulley.id)
    }

    @Test
    fun requestedBackendsAreRequired() {
        val (catalog, profiles) = fixtureCatalog()

        assertFailsWith<IllegalArgumentException> {
            AotCompatibilitySelectionResolver.resolve(
                selection = AotCompatibilitySelection(),
                releaseCatalog = catalog,
                profiles = profiles,
                requestedBackends = emptySet(),
            )
        }
    }

    @Test
    fun catalogRejectsMissingBackendBindingsAndUnknownProfiles() {
        val (catalog, profiles) = fixtureCatalog()
        val incomplete = catalog.copy(
            ranges = catalog.ranges.map { range ->
                if (range.aotGeneration == 2) {
                    range.copy(profileIdsByBackend = range.profileIdsByBackend - WasmlineEngineKind.PULLEY)
                } else {
                    range
                }
            },
        )
        val mismatched = catalog.copy(
            ranges = catalog.ranges.map { range ->
                if (range.aotGeneration == 2) {
                    range.copy(
                        profileIdsByBackend = range.profileIdsByBackend +
                            (WasmlineEngineKind.CRANELIFT to range.profileIdsByBackend.getValue(WasmlineEngineKind.PULLEY)),
                    )
                } else {
                    range
                }
            },
        )
        val missingProfileId = catalog.ranges.last().profileIdsByBackend.getValue(WasmlineEngineKind.PULLEY)

        assertFailsWith<IllegalArgumentException> {
            resolve(incomplete, profiles, AotCompatibilitySelection())
        }
        assertFailsWith<IllegalArgumentException> {
            resolve(mismatched, profiles, AotCompatibilitySelection())
        }
        assertFailsWith<IllegalStateException> {
            resolve(
                catalog,
                profiles.filterNot { it.id == missingProfileId },
                AotCompatibilitySelection(),
            )
        }
    }

    @Test
    fun changedBackendsMustUseCanonicalOrder() {
        val (catalog, profiles) = fixtureCatalog()
        val reordered = catalog.copy(
            ranges = catalog.ranges.mapIndexed { index, range ->
                if (index == 0) {
                    range.copy(changedBackends = listOf(WasmlineEngineKind.PULLEY, WasmlineEngineKind.CRANELIFT))
                } else {
                    range
                }
            },
        )

        catalog.validate(requireProfileBindings = true, profiles = profiles)
        assertFailsWith<IllegalArgumentException> {
            reordered.validate(requireProfileBindings = true, profiles = profiles)
        }
    }

    private fun resolve(
        catalog: WasmlineAotReleaseCatalog,
        profiles: List<AotCompatibilityProfileSpec>,
        selection: AotCompatibilitySelection,
        backends: Set<WasmlineEngineKind> = setOf(WasmlineEngineKind.CRANELIFT, WasmlineEngineKind.PULLEY),
    ): AotCompatibilityResolution = AotCompatibilitySelectionResolver.resolve(
        selection = selection,
        releaseCatalog = catalog,
        profiles = profiles,
        requestedBackends = backends,
    )

    private fun fixtureCatalog(): Pair<WasmlineAotReleaseCatalog, List<AotCompatibilityProfileSpec>> {
        val generationOneCranelift = profile(
            backend = WasmlineEngineKind.CRANELIFT,
            distribution = "12.3.4.1",
            revision = "a".repeat(40),
        )
        val generationOnePulley = profile(
            backend = WasmlineEngineKind.PULLEY,
            distribution = "12.3.4.1",
            revision = "a".repeat(40),
        )
        val generationTwoCranelift = profile(
            backend = WasmlineEngineKind.CRANELIFT,
            distribution = "12.3.5.1",
            revision = "b".repeat(40),
        )
        val generationTwoPulley = profile(
            backend = WasmlineEngineKind.PULLEY,
            distribution = "12.3.5.1",
            revision = "b".repeat(40),
        )
        val generationThreeCranelift = generationTwoCranelift
        val generationThreePulley = generationTwoPulley.copy(engineConfigurationProfile = "fixture-pulley-v2")
            .withCanonicalId()
        val profiles = listOf(
            generationOneCranelift,
            generationOnePulley,
            generationTwoCranelift,
            generationTwoPulley,
            generationThreeCranelift,
            generationThreePulley,
        )
        val catalog = WasmlineAotReleaseCatalog(
            schemaVersion = 1,
            currentWasmlineVersion = "3.0.0",
            minimumSupportedWasmlineVersion = "1.0.0",
            ranges = listOf(
                range("1.0.0", 1, "12.3.4.1", generationOneCranelift, generationOnePulley),
                range("1.5.0", 2, "12.3.5.1", generationTwoCranelift, generationTwoPulley),
                range("2.5.0", 3, "12.3.5.1", generationThreeCranelift, generationThreePulley, listOf(WasmlineEngineKind.PULLEY)),
            ),
        )
        return catalog to profiles
    }

    private fun profile(backend: WasmlineEngineKind, distribution: String, revision: String): AotCompatibilityProfileSpec =
        AotCompatibilityProfileSpec(
            id = "pending",
            artifactBackend = backend,
            wasmtimeVersion = distribution.substringBeforeLast('.'),
            wasmtimeDistributionVersion = distribution,
            wasmtimeSourceRevision = revision,
            serializedArtifactFormatIdentity = "fixture-format-v1",
            compileProfileSchemaVersion = 1,
            engineConfigurationProfile = "fixture",
            introducedInWasmlineVersion = "1.0.0",
        ).withCanonicalId()

    private fun AotCompatibilityProfileSpec.withCanonicalId(): AotCompatibilityProfileSpec = copy(
        id = AotCompatibilityCatalog.calculateCompatibilityId(this),
    )

    private fun range(
        from: String,
        generation: Int,
        distribution: String,
        cranelift: AotCompatibilityProfileSpec,
        pulley: AotCompatibilityProfileSpec,
        changedBackends: List<WasmlineEngineKind> = WasmlineEngineKind.entries.sortedBy(WasmlineEngineKind::name),
    ): WasmlineAotReleaseRange = WasmlineAotReleaseRange(
        fromWasmlineVersion = from,
        aotGeneration = generation,
        wasmtimeDistributionVersion = distribution,
        changedBackends = changedBackends,
        profileIdsByBackend = mapOf(
            WasmlineEngineKind.CRANELIFT to cranelift.id,
            WasmlineEngineKind.PULLEY to pulley.id,
        ),
    )
}
