package crow.wasmline.cli

import crow.wasmline.plugin.core.aot.AotCompatibilitySelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies CLI AOT selector parsing and range validation.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
class CliAotSelectionTest {
    @Test
    fun parsesNamedSelectors() {
        assertEquals(AotCompatibilitySelection.Current, parseCliAotSelection("current", emptyList()))
        assertEquals(AotCompatibilitySelection.Minimum, parseCliAotSelection("minimum", emptyList()))
        assertEquals(AotCompatibilitySelection.All, parseCliAotSelection("all", emptyList()))
    }

    @Test
    fun parsesInclusiveVersionRanges() {
        val selection = parseCliAotSelection("versionRanges", listOf("1.0.0..1.20.0", "2.0.0..2.1.0"))

        assertEquals(
            AotCompatibilitySelection.VersionRanges(
                listOf(
                    crow.wasmline.plugin.core.aot.WasmlineVersionRange("1.0.0", "1.20.0"),
                    crow.wasmline.plugin.core.aot.WasmlineVersionRange("2.0.0", "2.1.0"),
                ),
            ),
            selection,
        )
    }

    @Test
    fun rejectsMissingAndUnknownSelectors() {
        assertFailsWith<IllegalArgumentException> { parseCliAotSelection(null, emptyList()) }
        assertFailsWith<IllegalArgumentException> { parseCliAotSelection("", emptyList()) }
        assertFailsWith<IllegalArgumentException> { parseCliAotSelection("future", emptyList()) }
    }

    @Test
    fun rejectsMalformedAndEmptyRanges() {
        assertFailsWith<IllegalArgumentException> {
            parseCliAotSelection("versionRanges", emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            parseCliAotSelection("versionRanges", listOf("1.0.0"))
        }
        assertFailsWith<IllegalArgumentException> {
            parseCliAotSelection("versionRanges", listOf("2.0.0..1.0.0"))
        }
    }

    @Test
    fun rejectsRangesWithNamedNonRangeSelectors() {
        for (selector in listOf("current", "minimum", "all")) {
            assertFailsWith<IllegalArgumentException> {
                parseCliAotSelection(selector, listOf("1.0.0..1.0.0"))
            }
        }
    }

    @Test
    fun sharedDecoderRejectsRangesWithNamedSelectors() {
        assertFailsWith<IllegalArgumentException> {
            crow.wasmline.plugin.core.aot.decodeAotCompatibilitySelection(
                "current",
                listOf("1.0.0\u00001.0.0"),
            )
        }
    }
}
