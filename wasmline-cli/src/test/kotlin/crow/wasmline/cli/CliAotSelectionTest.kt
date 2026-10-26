package crow.wasmline.cli

import crow.wasmline.WasmlineEngineKind
import crow.wasmline.plugin.core.aot.AotCompatibilityCatalog
import crow.wasmline.plugin.core.aot.AotCompatibilityResolution
import crow.wasmline.plugin.core.aot.AotCompatibilitySelection
import crow.wasmline.plugin.core.aot.WasmlineVersionRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies the collection-only CLI AOT generation grammar and its local catalog resolution.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
class CliAotSelectionTest {
    @Test
    fun omittedGenerationTokensSelectEveryLocalGeneration() {
        assertEquals(AotCompatibilitySelection(), parseCliAotSelection(emptyList()))

        val resolution = resolve(emptyList())

        assertEquals(localGenerations(), resolution.selectedGenerations)
        assertEquals("generations", resolution.selection.diagnosticName())
    }

    @Test
    fun pointTokensSelectTheGenerationCoveringThem() {
        assertEquals(
            AotCompatibilitySelection(listOf(WasmlineVersionRange("1.0.0", "1.0.0"))),
            parseCliAotSelection(listOf("1.0.0")),
        )
        assertEquals(
            AotCompatibilitySelection(listOf(WasmlineVersionRange("latest", "latest"))),
            parseCliAotSelection(listOf("latest")),
        )

        val firstRangeStart = AotCompatibilityCatalog.releaseCatalog().ranges.first().fromWasmlineVersion
        val currentVersion = AotCompatibilityCatalog.releaseCatalog().currentWasmlineVersion
        assertEquals(listOf(localGenerations().first()), resolve(listOf(firstRangeStart)).selectedGenerations)
        assertEquals(listOf(localGenerations().last()), resolve(listOf(currentVersion)).selectedGenerations)
        assertEquals(
            resolve(listOf(currentVersion)).selectedGenerations,
            resolve(listOf("latest")).selectedGenerations,
        )
    }

    @Test
    fun closedRangesAndRepeatedTokensUnionIntoDeduplicatedGenerations() {
        assertEquals(
            AotCompatibilitySelection(
                listOf(
                    WasmlineVersionRange("1.0.0", "1.0.1"),
                    WasmlineVersionRange("1.0.0", "latest"),
                    WasmlineVersionRange("latest", "latest"),
                ),
            ),
            parseCliAotSelection(listOf("1.0.0..1.0.1", "1.0.0..latest", "latest")),
        )

        val minimumVersion = AotCompatibilityCatalog.releaseCatalog().minimumSupportedWasmlineVersion
        val resolution = resolve(
            listOf(minimumVersion, "$minimumVersion..latest", "latest", "$minimumVersion..$minimumVersion", "latest"),
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
    fun pointsAndRangesOutsideTheLocalCatalogFailWithoutClipping() {
        val minimumVersion = AotCompatibilityCatalog.releaseCatalog().minimumSupportedWasmlineVersion
        val currentVersion = AotCompatibilityCatalog.releaseCatalog().currentWasmlineVersion
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
            "1.0.0,1.0.1",
            "1.0.0 .. 1.0.1",
            "1.0.1..1.0.0",
            "..1.0.1",
            "1.0.1..",
            "1.0.0..1.0.1 ",
            "1.0.1..1.0.0..1.0.2",
            "latest..latest",
            "latest..1.0.1",
            "current",
            "minimum",
            "all",
            "versionRanges",
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
