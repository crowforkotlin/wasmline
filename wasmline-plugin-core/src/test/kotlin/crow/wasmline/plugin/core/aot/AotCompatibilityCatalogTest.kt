package crow.wasmline.plugin.core.aot

import crow.wasmline.WasmlineEngineKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Verifies release-range selection and backend-specific AOT identities.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
class AotCompatibilityCatalogTest {
    @Test
    fun currentSelectsOnlyTheFinalGeneration() {
        val (catalog, profiles) = fixtureCatalog()

        val resolution = AotCompatibilitySelectionResolver.resolve(
            selection = AotCompatibilitySelection.Current,
            manifestMinimumWasmlineVersion = "1.0.0",
            releaseCatalog = catalog,
            profiles = profiles,
            requestedBackends = setOf(WasmlineEngineKind.CRANELIFT, WasmlineEngineKind.PULLEY),
        )

        assertEquals(listOf(3), resolution.selectedGenerations)
        assertEquals(2, resolution.profiles.size)
    }

    @Test
    fun minimumAndAllDeduplicateProfilesByGeneration() {
        val (catalog, profiles) = fixtureCatalog()

        val minimum = AotCompatibilitySelectionResolver.resolve(
            selection = AotCompatibilitySelection.Minimum,
            manifestMinimumWasmlineVersion = "1.0.0",
            releaseCatalog = catalog,
            profiles = profiles,
            requestedBackends = setOf(WasmlineEngineKind.CRANELIFT, WasmlineEngineKind.PULLEY),
        )
        val all = AotCompatibilitySelectionResolver.resolve(
            selection = AotCompatibilitySelection.All,
            manifestMinimumWasmlineVersion = "1.0.0",
            releaseCatalog = catalog,
            profiles = profiles,
            requestedBackends = setOf(WasmlineEngineKind.CRANELIFT, WasmlineEngineKind.PULLEY),
        )

        assertEquals(listOf(1, 2, 3), minimum.selectedGenerations)
        assertEquals(listOf(1, 2, 3), all.selectedGenerations)
        assertEquals(5, minimum.profiles.size)
        assertEquals(minimum.profiles.map { it.id }.distinct().size, minimum.profiles.size)
    }

    @Test
    fun versionRangesUseClosedBoundariesAndMergeOverlaps() {
        val (catalog, profiles) = fixtureCatalog()

        val resolution = AotCompatibilitySelectionResolver.resolve(
            selection = AotCompatibilitySelection.VersionRanges(
                listOf(
                    WasmlineVersionRange("1.5.0", "2.5.0"),
                    WasmlineVersionRange("2.0.0", "3.0.0"),
                ),
            ),
            manifestMinimumWasmlineVersion = "1.0.0",
            releaseCatalog = catalog,
            profiles = profiles,
            requestedBackends = setOf(WasmlineEngineKind.PULLEY),
        )

        assertEquals(listOf(2, 3), resolution.selectedGenerations)
        assertEquals(2, resolution.profiles.size)
    }

    @Test
    fun allRejectsGenerationsEntirelyBelowManifestMinimum() {
        val (catalog, profiles) = fixtureCatalog()

        val failure = assertFailsWith<IllegalArgumentException> {
            AotCompatibilitySelectionResolver.resolve(
                selection = AotCompatibilitySelection.All,
                manifestMinimumWasmlineVersion = "2.0.0",
                releaseCatalog = catalog,
                profiles = profiles,
                requestedBackends = setOf(WasmlineEngineKind.CRANELIFT),
            )
        }

        assertTrue(failure.message.orEmpty().contains("entirely below"))
    }

    @Test
    fun customRangesRejectGenerationsEntirelyBelowManifestMinimum() {
        val (catalog, profiles) = fixtureCatalog()

        val failure = assertFailsWith<IllegalArgumentException> {
            AotCompatibilitySelectionResolver.resolve(
                selection = AotCompatibilitySelection.VersionRanges(
                    listOf(WasmlineVersionRange("1.0.0", "1.4.0")),
                ),
                manifestMinimumWasmlineVersion = "2.0.0",
                releaseCatalog = catalog,
                profiles = profiles,
                requestedBackends = setOf(WasmlineEngineKind.CRANELIFT),
            )
        }

        assertTrue(failure.message.orEmpty().contains("entirely below"))
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
    fun rangeOutsideCatalogIsRejected() {
        val (catalog, profiles) = fixtureCatalog()

        val failure = assertFailsWith<IllegalArgumentException> {
            AotCompatibilitySelectionResolver.resolve(
                selection = AotCompatibilitySelection.VersionRanges(
                    listOf(WasmlineVersionRange("4.0.0", "5.0.0")),
                ),
                manifestMinimumWasmlineVersion = "1.0.0",
                releaseCatalog = catalog,
                profiles = profiles,
                requestedBackends = setOf(WasmlineEngineKind.PULLEY),
            )
        }

        assertTrue(failure.message.orEmpty().contains("outside the local Wasmline catalog"))
    }

    @Test
    fun changedBackendsMustUseCanonicalOrder() {
        val (catalog, _) = fixtureCatalog()
        val invalid = catalog.copy(
            ranges = catalog.ranges.mapIndexed { index, range ->
                if (index == 0) {
                    range.copy(changedBackends = listOf(WasmlineEngineKind.PULLEY, WasmlineEngineKind.CRANELIFT))
                } else {
                    range
                }
            },
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            invalid.validate()
        }

        assertTrue(failure.message.orEmpty().contains("canonical order"))
    }

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
