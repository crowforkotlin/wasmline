package crow.wasmline.gradle.extensions

import crow.wasmline.gradle.WasmtimeTarget
import crow.wasmline.plugin.core.aot.AotCompatibilitySelection
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies explicit AOT compatibility selector configuration and defaults.
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
    fun aotSelectorIsUnsetUntilExplicitlyConfigured() {
        val extension = extension()

        assertEquals(null, extension.aotCompatibility.selectionOrNull())
        assertFalse(extension.aotCompatibility.suppressCompatibilityWarning.get())
    }

    @Test
    fun currentSelectorIsDistinctFromUnsetState() {
        val extension = extension()

        extension.aotCompatibility.current()

        assertEquals(AotCompatibilitySelection.Current, extension.aotCompatibility.selectionOrNull())
        assertTrue(extension.aotCompatibility.selectorKind.get() == "current")
    }

    @Test
    fun selectorsAreMutuallyExclusive() {
        val extension = extension()

        extension.aotCompatibility.minimum()

        assertFailsWith<IllegalStateException> {
            extension.aotCompatibility.all()
        }
    }

    @Test
    fun versionRangesRequireAtLeastOneInclusiveRange() {
        val extension = extension()

        assertFailsWith<IllegalArgumentException> {
            extension.aotCompatibility.versionRanges { }
        }

        extension.aotCompatibility.versionRanges {
            include(from = "1.0.0", through = "1.0.0")
        }
        assertEquals("versionRanges", extension.aotCompatibility.selectorKind.get())
    }

    private fun extension(): WasmtimeExtension {
        val project = ProjectBuilder.builder().build()
        return project.objects.newInstance(WasmtimeExtension::class.java)
    }
}
