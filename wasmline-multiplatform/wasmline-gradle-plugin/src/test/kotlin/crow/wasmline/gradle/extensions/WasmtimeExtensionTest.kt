package crow.wasmline.gradle.extensions

import crow.wasmline.gradle.WasmtimeTarget
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

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

    private fun extension(): WasmtimeExtension {
        val project = ProjectBuilder.builder().build()
        return project.objects.newInstance(WasmtimeExtension::class.java)
    }
}
