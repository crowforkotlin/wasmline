package crow.wasmline.cli

import crow.wasmline.WasmlineEngineKind
import crow.wasmline.plugin.core.aot.AotCompatibilityCatalog
import crow.wasmline.plugin.core.aot.AotCompatibilityResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
        val generations = localGenerations()
        assertTrue(generations.size >= 2, "Windows need at least two local AOT generations.")

        assertEquals(generations.takeLast(2), resolve(listOf("current:before=1")).selectedGenerations)
        assertEquals(generations.takeLast(2), resolve(listOf("current:before=1:after=0")).selectedGenerations)
        assertEquals(generations.takeLast(2), resolve(listOf("current:after=0:before=1")).selectedGenerations)
        assertEquals(generations.takeLast(2), resolve(listOf("latest:before=1")).selectedGenerations)
        assertEquals(generations.take(2), resolve(listOf("earliest:after=1")).selectedGenerations)
        assertEquals(generations.takeLast(1), resolve(listOf("current:before=0:after=0")).selectedGenerations)
        assertEquals(
            generations,
            resolve(listOf("earliest:after=${generations.lastIndex}", "current:before=1")).selectedGenerations,
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

    private fun localGenerations(): List<Int> =
        AotCompatibilityCatalog.releaseCatalog().ranges.map { range -> range.aotGeneration }

    private fun localPulleyProfileIds(): List<String> =
        AotCompatibilityCatalog.releaseCatalog().ranges.mapNotNull { range ->
            range.profileIdsByBackend[WasmlineEngineKind.PULLEY]
        }.distinct()
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
