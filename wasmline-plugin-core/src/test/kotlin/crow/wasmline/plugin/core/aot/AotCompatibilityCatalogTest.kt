package crow.wasmline.plugin.core.aot

import crow.wasmline.WasmlineEngineKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Verifies typed generation selection, strict local catalog bounds, and backend-specific AOT identities.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
class AotCompatibilityCatalogTest {
    @Test
    fun emptySelectionUsesTheCurrentGenerationOfTheIntroducedRelease() {
        val (catalog, profiles) = syntheticCatalog()

        assertResolution(resolve(catalog, profiles, AotCompatibilitySelection()), catalog, listOf(3))
        // The anchor is resolved by generation, not by exact Maven release version.
        assertResolution(
            resolve(catalog, profiles, AotCompatibilitySelection(), currentWasmlineVersion = "1.2.0"),
            catalog,
            listOf(3),
        )
        assertResolution(
            resolve(catalog, profiles, AotCompatibilitySelection(), currentWasmlineVersion = "1.2.99"),
            catalog,
            listOf(3),
        )
        assertResolution(
            resolve(catalog, profiles, AotCompatibilitySelection(), currentWasmlineVersion = "1.0.0"),
            catalog,
            listOf(1),
        )
        assertResolution(
            resolve(catalog, profiles, AotCompatibilitySelection(), currentWasmlineVersion = "1.3.99"),
            catalog,
            listOf(4),
        )
    }

    @Test
    fun explicitCurrentWindowIncludesAdjacentGenerations() {
        val (catalog, profiles) = syntheticCatalog()

        val before = resolve(catalog, profiles, selection(AotGenerations.Current(before = 1)))
        val after = resolve(catalog, profiles, selection(AotGenerations.Current(after = 1)))
        val both = resolve(catalog, profiles, selection(AotGenerations.Current(before = 1, after = 1)))
        val wholeCatalog = resolve(catalog, profiles, selection(AotGenerations.Current(before = 2, after = 2)))
        // Window counts are AOT generations, so either release version inside generation 3 anchors alike.
        val atGenerationStart = resolve(
            catalog,
            profiles,
            selection(AotGenerations.Current(before = 1, after = 1)),
            currentWasmlineVersion = "1.2.0",
        )
        val atGenerationEnd = resolve(
            catalog,
            profiles,
            selection(AotGenerations.Current(before = 1, after = 1)),
            currentWasmlineVersion = "1.2.99",
        )

        assertResolution(before, catalog, listOf(2, 3))
        assertResolution(after, catalog, listOf(3, 4))
        assertResolution(both, catalog, listOf(2, 3, 4))
        assertResolution(wholeCatalog, catalog, listOf(1, 2, 3, 4, 5))
        assertResolution(atGenerationStart, catalog, listOf(2, 3, 4))
        assertEquals(atGenerationStart.selectedGenerations, atGenerationEnd.selectedGenerations)
    }

    @Test
    fun zeroGenerationCountsKeepOnlyTheAnchorGeneration() {
        val (catalog, profiles) = syntheticCatalog()

        assertResolution(
            resolve(catalog, profiles, selection(AotGenerations.Current(before = 0, after = 0))),
            catalog,
            listOf(3),
        )
        // No-arg invocation of a singleton window keeps only its anchor generation.
        assertResolution(resolve(catalog, profiles, selection(AotGenerations.Current())), catalog, listOf(3))
        assertResolution(resolve(catalog, profiles, selection(AotGenerations.Latest(before = 0))), catalog, listOf(5))
        assertResolution(resolve(catalog, profiles, selection(AotGenerations.Latest())), catalog, listOf(5))
        assertResolution(resolve(catalog, profiles, selection(AotGenerations.Earliest(after = 0))), catalog, listOf(1))
        assertResolution(resolve(catalog, profiles, selection(AotGenerations.Earliest())), catalog, listOf(1))
    }

    @Test
    fun latestSelectsTheNewestLocalGenerationAndEarlierNeighbors() {
        val (catalog, profiles) = syntheticCatalog()

        assertResolution(resolve(catalog, profiles, selection(AotGenerations.Latest)), catalog, listOf(5))
        assertResolution(
            resolve(catalog, profiles, selection(AotGenerations.Latest(before = 1))),
            catalog,
            listOf(4, 5),
        )
        assertResolution(
            resolve(catalog, profiles, selection(AotGenerations.Latest(before = 4))),
            catalog,
            listOf(1, 2, 3, 4, 5),
        )
    }

    @Test
    fun earliestSelectsTheOldestLocalGenerationAndLaterNeighbors() {
        val (catalog, profiles) = syntheticCatalog()

        assertResolution(resolve(catalog, profiles, selection(AotGenerations.Earliest)), catalog, listOf(1))
        assertResolution(
            resolve(catalog, profiles, selection(AotGenerations.Earliest(after = 1))),
            catalog,
            listOf(1, 2),
        )
        assertResolution(
            resolve(catalog, profiles, selection(AotGenerations.Earliest(after = 4))),
            catalog,
            listOf(1, 2, 3, 4, 5),
        )
    }

    @Test
    fun allSelectsEveryRetainedGeneration() {
        val (catalog, profiles) = syntheticCatalog()
        val pulleyOnly = setOf(WasmlineEngineKind.PULLEY)

        val all = resolve(catalog, profiles, selection(AotGenerations.All))
        val pulleyArtifacts = resolve(catalog, profiles, selection(AotGenerations.All()), backends = pulleyOnly)

        // All is opt-in and resolves every retained generation for the requested backends.
        assertResolution(all, catalog, listOf(1, 2, 3, 4, 5))
        assertResolution(pulleyArtifacts, catalog, listOf(1, 2, 3, 4, 5), backends = pulleyOnly)
    }

    @Test
    fun packagedReleaseAnchorMakesCurrentAndLatestCoincide() {
        val (catalog, profiles) = syntheticCatalog()

        // Omitting the explicit anchor uses the catalog's packaged current release version.
        val packaged = AotCompatibilitySelectionResolver.resolve(
            selection = selection(AotGenerations.Current),
            releaseCatalog = catalog,
            profiles = profiles,
            requestedBackends = ALL_BACKENDS,
        )
        val middleAnchor = resolve(catalog, profiles, selection(AotGenerations.Current))
        val latest = resolve(catalog, profiles, selection(AotGenerations.Latest))

        assertResolution(packaged, catalog, listOf(5))
        assertEquals(packaged.selectedGenerations, latest.selectedGenerations)
        assertResolution(middleAnchor, catalog, listOf(3))
        assertNotEquals(packaged.selectedGenerations, middleAnchor.selectedGenerations)
    }

    @Test
    fun concreteVersionPointsSelectTheirOwnGeneration() {
        val (catalog, profiles) = syntheticCatalog()

        assertResolution(
            resolve(catalog, profiles, selection(AotGenerations.Versions(version = "1.0.0"))),
            catalog,
            listOf(1),
        )
        assertResolution(
            resolve(catalog, profiles, selection(AotGenerations.Versions(version = "1.2.7"))),
            catalog,
            listOf(3),
        )
        assertResolution(
            resolve(catalog, profiles, selection(AotGenerations.Versions(version = "1.4.0"))),
            catalog,
            listOf(5),
        )
        // Equal bounds select their own generation rather than the current generation.
        assertResolution(
            resolve(catalog, profiles, selection(AotGenerations.Versions(min = "1.0.0", max = "1.0.0"))),
            catalog,
            listOf(1),
        )
    }

    @Test
    fun concreteVersionRangesStayWithinOrCrossGenerationBoundaries() {
        val (catalog, profiles) = syntheticCatalog()

        assertResolution(
            resolve(catalog, profiles, selection(AotGenerations.Versions(min = "1.1.1", max = "1.1.9"))),
            catalog,
            listOf(2),
        )
        assertResolution(
            resolve(catalog, profiles, selection(AotGenerations.Versions(min = "1.2.0", max = "1.3.9"))),
            catalog,
            listOf(3, 4),
        )
        assertResolution(
            resolve(catalog, profiles, selection(AotGenerations.Versions(min = "1.0.0", max = "1.4.0"))),
            catalog,
            listOf(1, 2, 3, 4, 5),
        )
        // Concrete bounds may select generations before or after the current anchor.
        assertResolution(
            resolve(catalog, profiles, selection(AotGenerations.Versions(min = "1.0.0", max = "1.0.9"))),
            catalog,
            listOf(1),
        )
        assertResolution(
            resolve(catalog, profiles, selection(AotGenerations.Versions(min = "1.3.0", max = "1.3.9"))),
            catalog,
            listOf(4),
        )
    }

    @Test
    fun versionCollectionsUnionGenerationsAndReuseProfiles() {
        val (catalog, profiles) = syntheticCatalog()

        val union = resolve(
            catalog,
            profiles,
            selection(AotGenerations.Versions(versions = listOf("1.0.9", "1.2.7", "1.3.1"))),
        )
        val repeatedGeneration = resolve(
            catalog,
            profiles,
            selection(AotGenerations.Versions(versions = listOf("1.2.4", "1.2.9"))),
        )

        assertResolution(union, catalog, listOf(1, 3, 4))
        assertResolution(repeatedGeneration, catalog, listOf(3))
    }

    @Test
    fun repeatedStrategiesUnionGenerationsAndDeduplicateProfiles() {
        val (catalog, profiles) = syntheticCatalog()

        val union = resolve(
            catalog,
            profiles,
            selection(
                AotGenerations.Current,
                AotGenerations.Versions(version = "1.2.7"),
                AotGenerations.Versions(version = "1.0.0"),
                AotGenerations.Current(after = 1),
            ),
        )
        val repeatedCurrent = resolve(
            catalog,
            profiles,
            selection(
                AotGenerations.Current,
                AotGenerations.Current,
                AotGenerations.Versions(version = "1.2.0"),
            ),
        )
        val mixed = resolve(
            catalog,
            profiles,
            selection(
                AotGenerations.Earliest,
                AotGenerations.Latest(before = 1),
                AotGenerations.Current(after = 1),
            ),
        )

        assertResolution(union, catalog, listOf(1, 3, 4))
        assertResolution(repeatedCurrent, catalog, listOf(3))
        assertResolution(mixed, catalog, listOf(1, 3, 4, 5))
    }

    @Test
    fun negativeGenerationCountsAreRejected() {
        assertFailsWith<IllegalArgumentException> { AotGenerations.Current(before = -1) }
        assertFailsWith<IllegalArgumentException> { AotGenerations.Current(after = -1) }
        assertFailsWith<IllegalArgumentException> { AotGenerations.Current(before = 1, after = -1) }
        assertFailsWith<IllegalArgumentException> { AotGenerations.Latest(before = -1) }
        assertFailsWith<IllegalArgumentException> { AotGenerations.Earliest(after = -1) }
    }

    @Test
    fun unavailableGenerationNeighborsAreRejectedWithoutClipping() {
        val (catalog, profiles) = syntheticCatalog()

        assertResolution(
            resolve(catalog, profiles, selection(AotGenerations.Current(before = 2))),
            catalog,
            listOf(1, 2, 3),
        )
        assertResolution(
            resolve(catalog, profiles, selection(AotGenerations.Current(after = 2))),
            catalog,
            listOf(3, 4, 5),
        )

        listOf(
            AotGenerations.Current(before = 3),
            AotGenerations.Current(after = 3),
            AotGenerations.Current(before = 2, after = 3),
            AotGenerations.Latest(before = 5),
            AotGenerations.Earliest(after = 5),
        ).forEach { unavailable ->
            assertFailsWith<IllegalArgumentException> {
                resolve(catalog, profiles, selection(unavailable))
            }
        }

        // A packaged build tool cannot know a successor generation that is not in its local catalog.
        assertFailsWith<IllegalArgumentException> {
            resolve(
                catalog,
                profiles,
                selection(AotGenerations.Current(after = 1)),
                currentWasmlineVersion = CATALOG_CURRENT_VERSION,
            )
        }
        assertResolution(
            resolve(
                catalog,
                profiles,
                selection(AotGenerations.Current(before = 4)),
                currentWasmlineVersion = CATALOG_CURRENT_VERSION,
            ),
            catalog,
            listOf(1, 2, 3, 4, 5),
        )
    }

    @Test
    fun extremeGenerationCountsAreRejectedWithoutOverflowOrClipping() {
        val (catalog, profiles) = syntheticCatalog()

        listOf(
            AotGenerations.Current(before = Int.MAX_VALUE),
            AotGenerations.Current(after = Int.MAX_VALUE),
            AotGenerations.Current(before = Int.MAX_VALUE, after = Int.MAX_VALUE),
            AotGenerations.Current(before = Int.MAX_VALUE - 1, after = Int.MAX_VALUE - 2),
            AotGenerations.Latest(before = Int.MAX_VALUE),
            AotGenerations.Earliest(after = Int.MAX_VALUE),
        ).forEach { extreme ->
            assertFailsWith<IllegalArgumentException> {
                resolve(catalog, profiles, selection(extreme))
            }
        }
    }

    @Test
    fun concreteVersionsOutsideTheLocalCatalogAreRejected() {
        val (catalog, profiles) = syntheticCatalog()

        listOf(
            AotGenerations.Versions(version = "0.9.9"),
            AotGenerations.Versions(min = "0.9.9", max = "1.0.0"),
            AotGenerations.Versions(min = "0.5.0", max = "1.2.0"),
            AotGenerations.Versions(version = "1.4.1"),
            AotGenerations.Versions(min = "1.0.0", max = "1.4.1"),
            AotGenerations.Versions(min = "1.3.0", max = "2.0.0"),
        ).forEach { outside ->
            assertFailsWith<IllegalArgumentException> {
                resolve(catalog, profiles, selection(outside))
            }
        }
    }

    @Test
    fun invertedOrEmptyConcreteSelectorsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            AotGenerations.Versions(min = "1.3.0", max = "1.2.0")
        }
        assertFailsWith<IllegalArgumentException> {
            WasmlineVersionRange(from = "2.0.0", through = "1.0.0")
        }
        assertFailsWith<IllegalArgumentException> {
            AotGenerations.Versions(versions = emptyList<String>())
        }
    }

    @Test
    fun symbolicLatestIsRejectedByEveryConcreteVersionSelector() {
        val (catalog, profiles) = syntheticCatalog()

        assertFailsWith<IllegalArgumentException> { AotGenerations.Versions(version = "latest") }
        assertFailsWith<IllegalArgumentException> { AotGenerations.Versions(versions = listOf("latest")) }
        assertFailsWith<IllegalArgumentException> { AotGenerations.Versions(min = "1.0.0", max = "latest") }
        assertFailsWith<IllegalArgumentException> { AotGenerations.Versions(min = "latest", max = "latest") }
        assertFailsWith<IllegalArgumentException> { WasmlineVersionRange(from = "1.0.0", through = "latest") }
        assertFailsWith<IllegalArgumentException> {
            resolve(catalog, profiles, AotCompatibilitySelection(), currentWasmlineVersion = "latest")
        }
    }

    @Test
    fun resolverCurrentAnchorMustBeAConcreteCatalogVersion() {
        val (catalog, profiles) = syntheticCatalog()

        listOf("0.9.9", "1.4.1", "latest", "1.2", "v1.2.7").forEach { anchor ->
            assertFailsWith<IllegalArgumentException> {
                resolve(catalog, profiles, AotCompatibilitySelection(), currentWasmlineVersion = anchor)
            }
        }
    }

    @Test
    fun versionsCompareNumericallyWithoutLeadingZeroOrLengthLimits() {
        assertEquals(0, compareWasmlineVersions("01.0.0", "1.0.0"))
        assertEquals(0, compareWasmlineVersions("1.0000000000000000000000000000002.0", "1.2.0"))
        assertTrue(compareWasmlineVersions("1.9999999999999999999999999999999.0", "1.2.0") > 0)
        val (catalog, profiles) = syntheticCatalog()

        val resolution = resolve(
            catalog,
            profiles,
            selection(AotGenerations.Versions(min = "01.2.0", max = "01.3.0")),
            currentWasmlineVersion = "01.2.07",
        )

        assertResolution(resolution, catalog, listOf(3, 4))
    }

    @Test
    fun backendProfilesHaveDifferentCompatibilityIdentities() {
        val (catalog, profiles) = syntheticCatalog()
        val pulley = profiles.first { it.artifactBackend == WasmlineEngineKind.PULLEY }
        val cranelift = profiles.first { it.artifactBackend == WasmlineEngineKind.CRANELIFT }

        assertNotEquals(pulley.id, cranelift.id)
        assertEquals(catalog.ranges.first().profileIdsByBackend[WasmlineEngineKind.PULLEY], pulley.id)
    }

    @Test
    fun requestedBackendsAreRequired() {
        val (catalog, profiles) = syntheticCatalog()

        listOf(AotGenerations.Current, AotGenerations.All).forEach { strategy ->
            assertFailsWith<IllegalArgumentException> {
                resolve(catalog, profiles, selection(strategy), backends = emptySet())
            }
        }
    }

    @Test
    fun catalogRejectsMissingBackendBindingsAndUnknownProfiles() {
        val (catalog, profiles) = syntheticCatalog()
        val incomplete = catalog.copy(
            ranges = catalog.ranges.map { range ->
                if (range.aotGeneration == 3) {
                    range.copy(profileIdsByBackend = range.profileIdsByBackend - WasmlineEngineKind.PULLEY)
                } else {
                    range
                }
            },
        )
        val mismatched = catalog.copy(
            ranges = catalog.ranges.map { range ->
                if (range.aotGeneration == 3) {
                    range.copy(
                        profileIdsByBackend = range.profileIdsByBackend +
                            (WasmlineEngineKind.CRANELIFT to range.profileIdsByBackend.getValue(WasmlineEngineKind.PULLEY)),
                    )
                } else {
                    range
                }
            },
        )
        val missingProfileId = catalog.ranges[2].profileIdsByBackend.getValue(WasmlineEngineKind.PULLEY)

        assertFailsWith<IllegalArgumentException> {
            resolve(incomplete, profiles, selection(AotGenerations.Current))
        }
        assertFailsWith<IllegalArgumentException> {
            resolve(mismatched, profiles, selection(AotGenerations.Current))
        }
        assertFailsWith<IllegalStateException> {
            resolve(
                catalog,
                profiles.filterNot { it.id == missingProfileId },
                selection(AotGenerations.Current),
            )
        }
    }

    @Test
    fun changedBackendsMustUseCanonicalOrder() {
        val (catalog, profiles) = syntheticCatalog()
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

    private fun selection(vararg strategies: AotGenerations): AotCompatibilitySelection =
        AotCompatibilitySelection(strategies.toList())

    private fun resolve(
        catalog: WasmlineAotReleaseCatalog,
        profiles: List<AotCompatibilityProfileSpec>,
        selection: AotCompatibilitySelection,
        backends: Set<WasmlineEngineKind> = ALL_BACKENDS,
        currentWasmlineVersion: String = MIDDLE_CURRENT_VERSION,
    ): AotCompatibilityResolution = AotCompatibilitySelectionResolver.resolve(
        selection = selection,
        releaseCatalog = catalog,
        profiles = profiles,
        requestedBackends = backends,
        currentWasmlineVersion = currentWasmlineVersion,
    )

    private fun assertResolution(
        resolution: AotCompatibilityResolution,
        catalog: WasmlineAotReleaseCatalog,
        generations: List<Int>,
        backends: Set<WasmlineEngineKind> = ALL_BACKENDS,
    ) {
        assertEquals(generations, resolution.selectedGenerations)
        val boundIds = boundProfileIds(catalog, generations, backends)
        assertEquals(boundIds, resolution.profiles.map { it.id }.toSet())
        assertEquals(boundIds.size, resolution.profiles.size)
        assertEquals(backends, resolution.requestedBackends)
    }

    private fun boundProfileIds(
        catalog: WasmlineAotReleaseCatalog,
        generations: List<Int>,
        backends: Set<WasmlineEngineKind>,
    ): Set<String> = catalog.ranges
        .filter { range -> range.aotGeneration in generations }
        .flatMap { range -> backends.mapNotNull { backend -> range.profileIdsByBackend[backend] } }
        .toSet()

    private fun syntheticCatalog(): Pair<WasmlineAotReleaseCatalog, List<AotCompatibilityProfileSpec>> {
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
        val generationThreePulley = generationTwoPulley.copy(engineConfigurationProfile = "fixture-pulley-v2")
            .withCanonicalId()
        val generationFourCranelift = profile(
            backend = WasmlineEngineKind.CRANELIFT,
            distribution = "12.3.6.1",
            revision = "c".repeat(40),
        )
        val generationFourPulley = profile(
            backend = WasmlineEngineKind.PULLEY,
            distribution = "12.3.6.1",
            revision = "c".repeat(40),
        )
        val generationFivePulley = generationFourPulley.copy(engineConfigurationProfile = "fixture-pulley-v2")
            .withCanonicalId()
        val profiles = listOf(
            generationOneCranelift,
            generationOnePulley,
            generationTwoCranelift,
            generationTwoPulley,
            generationThreePulley,
            generationFourCranelift,
            generationFourPulley,
            generationFivePulley,
        )
        val catalog = WasmlineAotReleaseCatalog(
            schemaVersion = 1,
            currentWasmlineVersion = CATALOG_CURRENT_VERSION,
            minimumSupportedWasmlineVersion = CATALOG_MINIMUM_VERSION,
            ranges = listOf(
                range("1.0.0", 1, "12.3.4.1", generationOneCranelift, generationOnePulley),
                range("1.1.0", 2, "12.3.5.1", generationTwoCranelift, generationTwoPulley),
                range(
                    "1.2.0",
                    3,
                    "12.3.5.1",
                    generationTwoCranelift,
                    generationThreePulley,
                    listOf(WasmlineEngineKind.PULLEY),
                ),
                range("1.3.0", 4, "12.3.6.1", generationFourCranelift, generationFourPulley),
                range(
                    "1.4.0",
                    5,
                    "12.3.6.1",
                    generationFourCranelift,
                    generationFivePulley,
                    listOf(WasmlineEngineKind.PULLEY),
                ),
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
        changedBackends: List<WasmlineEngineKind> = ALL_CHANGED_BACKENDS,
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

    private companion object {
        const val CATALOG_CURRENT_VERSION = "1.4.0"
        const val CATALOG_MINIMUM_VERSION = "1.0.0"
        const val MIDDLE_CURRENT_VERSION = "1.2.7"
        val ALL_BACKENDS: Set<WasmlineEngineKind> = setOf(WasmlineEngineKind.CRANELIFT, WasmlineEngineKind.PULLEY)
        val ALL_CHANGED_BACKENDS: List<WasmlineEngineKind> =
            WasmlineEngineKind.entries.sortedBy(WasmlineEngineKind::name)
    }
}
