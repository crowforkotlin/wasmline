package crow.wasmline.gradle.extensions

import crow.wasmline.WasmlineEngineKind
import crow.wasmline.gradle.WasmtimeTarget
import crow.wasmline.plugin.core.aot.AotCompatibilityCatalog
import crow.wasmline.plugin.core.aot.AotCompatibilityResolution
import crow.wasmline.plugin.core.aot.AotCompatibilitySelection
import crow.wasmline.plugin.core.aot.decodeWasmlineVersionRange
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies local AOT generation selection and physical target configuration.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
class WasmtimeExtensionTest {
    @Test
    fun omittedTargetsUseAllSupportedTargets() {
        val extension = extension()

        assertEquals(WasmtimeTarget.ALL, extension.targets)
    }

    @Test
    fun typedTargetsCanReplaceTheDefaultConvention() {
        val extension = extension()
        val configuredTargets = listOf(
            WasmtimeTarget.PULLEY_64,
            WasmtimeTarget.AARCH64_ANDROID,
            WasmtimeTarget.X86_64_LINUX,
        )

        extension.targets = configuredTargets

        assertEquals(configuredTargets, extension.targets)
    }

    @Test
    fun emptyTargetsRestoreTheAllTargetsConvention() {
        val extension = extension()

        extension.targets = emptyList()

        assertEquals(WasmtimeTarget.ALL, extension.targets)
    }

    @Test
    fun duplicateTargetsAreRemovedInDeclarationOrder() {
        val extension = extension()

        extension.targets = listOf(
            WasmtimeTarget.AARCH64_ANDROID,
            WasmtimeTarget.PULLEY_64,
            WasmtimeTarget.AARCH64_ANDROID,
            WasmtimeTarget.PULLEY_64,
        )

        assertEquals(
            listOf(WasmtimeTarget.AARCH64_ANDROID, WasmtimeTarget.PULLEY_64),
            extension.targets,
        )
    }

    @Test
    fun omittedAotSelectionUsesEveryLocalGeneration() {
        assertEquals(localGenerations(), resolve(extension()).selectedGenerations)
    }

    @Test
    fun emptyCompatibilityBlockUsesEveryLocalGeneration() {
        val extension = extension()
        extension.aotCompatibility { }

        assertEquals(localGenerations(), resolve(extension).selectedGenerations)
    }

    @Test
    fun warningOnlyBlockDoesNotNarrowTheDefaultSelection() {
        val extension = extension()
        extension.aotCompatibility {
            suppressCompatibilityWarning.set(true)
        }

        assertEquals(localGenerations(), resolve(extension).selectedGenerations)
    }

    @Test
    fun firstExplicitPointReplacesImplicitAllSelection() {
        val extension = extension()
        val first = AotCompatibilityCatalog.releaseCatalog().ranges.first()
        extension.aotCompatibility.generations(first.fromWasmlineVersion)

        val resolution = resolve(extension)

        assertEquals(listOf(first.aotGeneration), resolution.selectedGenerations)
        assertEquals(
            listOf(first.profileIdsByBackend.getValue(WasmlineEngineKind.PULLEY)),
            resolution.profiles.map { it.id },
        )
    }

    @Test
    fun latestPointSelectsOnlyTheLocalCurrentGeneration() {
        val extension = extension()
        extension.aotCompatibility.generations("latest")

        assertEquals(
            listOf(AotCompatibilityCatalog.releaseCatalog().ranges.last().aotGeneration),
            resolve(extension).selectedGenerations,
        )
    }

    @Test
    fun repeatedPointCallsFormADeduplicatedUnion() {
        val extension = extension()
        val first = AotCompatibilityCatalog.releaseCatalog().ranges.first()
        val last = AotCompatibilityCatalog.releaseCatalog().ranges.last()
        extension.aotCompatibility {
            generations(first.fromWasmlineVersion)
            generations("latest")
            generations(first.fromWasmlineVersion)
            generations("latest")
        }

        val resolution = resolve(extension)

        assertEquals(listOf(first.aotGeneration, last.aotGeneration).distinct(), resolution.selectedGenerations)
        assertEquals(
            listOf(first, last).map { it.profileIdsByBackend.getValue(WasmlineEngineKind.PULLEY) }.distinct(),
            resolution.profiles.map { it.id },
        )
    }

    @Test
    fun closedRangesIncludeBothEndpointsAndMergeOverlaps() {
        val extension = extension()
        val catalog = AotCompatibilityCatalog.releaseCatalog()
        extension.aotCompatibility {
            generations(from = catalog.minimumSupportedWasmlineVersion, through = "latest")
            generations(from = catalog.currentWasmlineVersion, through = catalog.currentWasmlineVersion)
        }

        assertEquals(localGenerations(), resolve(extension).selectedGenerations)
    }

    @Test
    fun rejectsMalformedPointsAndSymbolicRangeStarts() {
        for (point in listOf("", "LATEST", "1.0", "1.0.0.1", "1.0.0-alpha", " 1.0.0")) {
            assertFailsWith<IllegalArgumentException>(point) {
                extension().aotCompatibility.generations(point)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            extension().aotCompatibility.generations(from = "latest", through = "latest")
        }
    }

    @Test
    fun rejectsReversedAndPartiallyOutOfCatalogRanges() {
        val catalog = AotCompatibilityCatalog.releaseCatalog()
        assertFailsWith<IllegalArgumentException> {
            extension().aotCompatibility.generations(
                from = catalog.currentWasmlineVersion,
                through = catalog.minimumSupportedWasmlineVersion,
            )
        }
        for ((from, through) in listOf(
            "0.0.0" to catalog.minimumSupportedWasmlineVersion,
            catalog.minimumSupportedWasmlineVersion to "999999999999999999999999999999999.0.0",
        )) {
            val extension = extension()
            extension.aotCompatibility.generations(from = from, through = through)

            assertFailsWith<IllegalArgumentException> { resolve(extension) }
        }
    }

    private fun localGenerations(): List<Int> =
        AotCompatibilityCatalog.releaseCatalog().ranges.map { it.aotGeneration }

    private fun resolve(extension: WasmtimeExtension): AotCompatibilityResolution =
        AotCompatibilityCatalog.resolveSelection(
            selection = AotCompatibilitySelection(
                extension.aotCompatibility.generationRanges.get().map(::decodeWasmlineVersionRange),
            ),
            artifactBackends = setOf(WasmlineEngineKind.PULLEY),
        )

    private fun extension(): WasmtimeExtension {
        val project = ProjectBuilder.builder().build()
        return project.objects.newInstance(WasmtimeExtension::class.java)
    }
}
