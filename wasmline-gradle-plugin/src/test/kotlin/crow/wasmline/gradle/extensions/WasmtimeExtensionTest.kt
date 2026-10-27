package crow.wasmline.gradle.extensions

import crow.wasmline.WasmlineEngineKind
import crow.wasmline.gradle.WasmtimeTarget
import crow.wasmline.plugin.core.aot.AotCompatibilityCatalog
import crow.wasmline.plugin.core.aot.AotCompatibilityResolution
import crow.wasmline.plugin.core.aot.AotCompatibilitySelection
import crow.wasmline.plugin.core.aot.AotGenerations
import crow.wasmline.plugin.core.aot.decodeAotGenerations
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

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
    fun omittedAotSelectionUsesOnlyTheCurrentReleaseGeneration() {
        val catalog = AotCompatibilityCatalog.releaseCatalog()

        val resolution = resolve(extension())

        assertEquals(listOf(catalog.ranges.last().aotGeneration), resolution.selectedGenerations)
        if (catalog.ranges.size > 1) {
            assertNotEquals(localGenerations(), resolution.selectedGenerations)
        }
    }

    @Test
    fun emptyCompatibilityBlockKeepsTheCurrentReleaseDefault() {
        val extension = extension()
        extension.aotCompatibility { }

        val currentGeneration = AotCompatibilityCatalog.releaseCatalog().ranges.last().aotGeneration
        assertEquals(listOf(currentGeneration), resolve(extension).selectedGenerations)
    }

    @Test
    fun warningOnlyBlockKeepsTheCurrentReleaseDefault() {
        val extension = extension()
        extension.aotCompatibility {
            suppressCompatibilityWarning.set(true)
        }

        assertEquals(
            listOf(AotCompatibilityCatalog.releaseCatalog().ranges.last().aotGeneration),
            resolve(extension).selectedGenerations,
        )
    }

    @Test
    fun firstExplicitStrategyReplacesTheImplicitDefault() {
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
    fun explicitAllSelectsEveryRetainedGeneration() {
        val extension = extension()
        extension.aotCompatibility.generations(AotGenerations.All)

        assertEquals(localGenerations(), resolve(extension).selectedGenerations)
    }

    @Test
    fun namedSingletonStrategiesSelectTheirAnchorGeneration() {
        val catalog = AotCompatibilityCatalog.releaseCatalog()

        assertEquals(
            listOf(catalog.ranges.last().aotGeneration),
            resolveWith(AotGenerations.Current).selectedGenerations,
        )
        assertEquals(
            listOf(catalog.ranges.last().aotGeneration),
            resolveWith(AotGenerations.Latest).selectedGenerations,
        )
        assertEquals(
            listOf(catalog.ranges.first().aotGeneration),
            resolveWith(AotGenerations.Earliest).selectedGenerations,
        )
    }

    @Test
    fun typedWindowsSelectTheirAnchorDirectionalNeighbors() {
        val catalog = AotCompatibilityCatalog.releaseCatalog()
        val lastIndex = catalog.ranges.lastIndex
        val generations = catalog.ranges.map { it.aotGeneration }
        val neighbor = if (lastIndex > 0) 1 else 0

        assertEquals(
            generations.takeLast(neighbor + 1),
            resolveWith(AotGenerations.Latest(before = neighbor)).selectedGenerations,
        )
        assertEquals(
            generations.take(neighbor + 1),
            resolveWith(AotGenerations.Earliest(after = neighbor)).selectedGenerations,
        )
        assertEquals(
            generations,
            resolveWith(AotGenerations.Latest(before = lastIndex)).selectedGenerations,
        )
        assertEquals(
            generations,
            resolveWith(AotGenerations.Earliest(after = lastIndex)).selectedGenerations,
        )
    }

    @Test
    fun typedWindowCanExpandBeyondTheImplicitCurrentDefault() {
        val catalog = AotCompatibilityCatalog.releaseCatalog()
        val extension = extension()
        extension.aotCompatibility.generations(AotGenerations.Current(before = catalog.ranges.lastIndex))

        val resolution = resolve(extension)

        assertEquals(localGenerations(), resolution.selectedGenerations)
        assertEquals(
            catalog.ranges.map { it.profileIdsByBackend.getValue(WasmlineEngineKind.PULLEY) }.toSet(),
            resolution.profiles.map { it.id }.toSet(),
        )
    }

    @Test
    fun windowBeyondTheLocalCatalogFailsAtResolution() {
        val extension = extension()
        extension.aotCompatibility.generations(
            AotGenerations.Current(after = AotCompatibilityCatalog.releaseCatalog().ranges.size),
        )

        assertFailsWith<IllegalArgumentException> { resolve(extension) }
    }

    @Test
    fun concreteVersionCollectionSelectsTheUnionOfItsGenerations() {
        val catalog = AotCompatibilityCatalog.releaseCatalog()
        val first = catalog.ranges.first()
        val last = catalog.ranges.last()
        val extension = extension()
        extension.aotCompatibility.generations(
            versions = listOf(first.fromWasmlineVersion, last.fromWasmlineVersion, first.fromWasmlineVersion),
        )

        assertEquals(
            listOf(first.aotGeneration, last.aotGeneration).distinct(),
            resolve(extension).selectedGenerations,
        )
    }

    @Test
    fun concreteMinMaxOverloadIncludesBothEndpoints() {
        val catalog = AotCompatibilityCatalog.releaseCatalog()
        val extension = extension()
        extension.aotCompatibility.generations(
            min = catalog.minimumSupportedWasmlineVersion,
            max = catalog.currentWasmlineVersion,
        )

        assertEquals(localGenerations(), resolve(extension).selectedGenerations)
    }

    @Test
    fun equalConcreteBoundsSelectThatVersionsGeneration() {
        val catalog = AotCompatibilityCatalog.releaseCatalog()
        val first = catalog.ranges.first()
        val extension = extension()
        extension.aotCompatibility.generations(min = first.fromWasmlineVersion, max = first.fromWasmlineVersion)

        assertEquals(listOf(first.aotGeneration), resolve(extension).selectedGenerations)
    }

    @Test
    fun repeatedStrategyCallsFormADeduplicatedUnion() {
        val extension = extension()
        val first = AotCompatibilityCatalog.releaseCatalog().ranges.first()
        val last = AotCompatibilityCatalog.releaseCatalog().ranges.last()
        extension.aotCompatibility {
            generations(first.fromWasmlineVersion)
            generations(AotGenerations.Current)
            generations(AotGenerations.Versions(listOf(first.fromWasmlineVersion)))
            generations(AotGenerations.Current)
        }

        val resolution = resolve(extension)

        assertEquals(listOf(first.aotGeneration, last.aotGeneration), resolution.selectedGenerations)
        assertEquals(
            listOf(first, last).map { it.profileIdsByBackend.getValue(WasmlineEngineKind.PULLEY) }.distinct(),
            resolution.profiles.map { it.id },
        )
    }

    @Test
    fun rejectsMalformedPointsAndSymbolicLatestStrings() {
        for (point in listOf("", "LATEST", "latest", "1.0", "1.0.0.1", "1.0.0-alpha", " 1.0.0")) {
            assertFailsWith<IllegalArgumentException>(point) {
                extension().aotCompatibility.generations(point)
            }
        }
    }

    @Test
    fun rejectsEmptyVersionCollections() {
        assertFailsWith<IllegalArgumentException> {
            extension().aotCompatibility.generations(versions = emptyList())
        }
    }

    @Test
    fun rejectsInvertedConcreteBounds() {
        val catalog = AotCompatibilityCatalog.releaseCatalog()
        assertFailsWith<IllegalArgumentException> {
            extension().aotCompatibility.generations(
                min = catalog.currentWasmlineVersion,
                max = catalog.minimumSupportedWasmlineVersion,
            )
        }
    }

    @Test
    fun rejectsOutOfCatalogConcreteBoundsAtResolution() {
        val catalog = AotCompatibilityCatalog.releaseCatalog()
        val extension = extension()
        extension.aotCompatibility.generations(min = "0.0.0", max = catalog.minimumSupportedWasmlineVersion)

        assertFailsWith<IllegalArgumentException> { resolve(extension) }
    }

    private fun localGenerations(): List<Int> = AotCompatibilityCatalog.releaseCatalog().ranges.map { it.aotGeneration }

    private fun resolveWith(strategy: AotGenerations): AotCompatibilityResolution {
        val extension = extension()
        extension.aotCompatibility.generations(strategy)
        return resolve(extension)
    }

    private fun resolve(extension: WasmtimeExtension): AotCompatibilityResolution = AotCompatibilityCatalog.resolveSelection(
        selection = AotCompatibilitySelection(
            strategies = extension.aotCompatibility.generationStrategies.get().map(::decodeAotGenerations),
        ),
        artifactBackends = setOf(WasmlineEngineKind.PULLEY),
    )

    private fun extension(): WasmtimeExtension {
        val project = ProjectBuilder.builder().build()
        return project.objects.newInstance(WasmtimeExtension::class.java)
    }
}
