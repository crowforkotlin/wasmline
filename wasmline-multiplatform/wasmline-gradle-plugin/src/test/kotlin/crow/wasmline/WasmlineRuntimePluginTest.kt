package crow.wasmline

import org.gradle.nativeplatform.MachineArchitecture
import org.gradle.nativeplatform.OperatingSystemFamily
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies native engine variant selection for JVM consumers.
 *
 * Date: 2026-08-26
 * Author: crowforkotlin
 */
class WasmlineRuntimePluginTest {
    @Test
    fun mapsSupportedJvmPlatformNames() {
        assertEquals(OperatingSystemFamily.LINUX, gradleOperatingSystem("Linux"))
        assertEquals(OperatingSystemFamily.MACOS, gradleOperatingSystem("Mac OS X"))
        assertEquals(OperatingSystemFamily.WINDOWS, gradleOperatingSystem("Windows 11"))
        assertEquals(MachineArchitecture.X86_64, gradleArchitecture("amd64"))
        assertEquals(MachineArchitecture.X86_64, gradleArchitecture("x86_64"))
        assertEquals(MachineArchitecture.ARM64, gradleArchitecture("aarch64"))
        assertEquals(MachineArchitecture.ARM64, gradleArchitecture("arm64"))
    }

    @Test
    fun rejectsUnsupportedJvmPlatformNames() {
        assertFailsWith<IllegalStateException> { gradleOperatingSystem("Plan 9") }
        assertFailsWith<IllegalStateException> { gradleArchitecture("riscv64") }
    }

    @Test
    fun configuresResolvableConsumerAttributes() {
        val project = ProjectBuilder.builder().build()
        val runtime = project.configurations.create("consumerRuntime") { configuration ->
            configuration.isCanBeResolved = true
        }

        project.pluginManager.apply(WasmlineRuntimePlugin::class.java)

        assertEquals(
            currentGradleOperatingSystem(),
            runtime.attributes.getAttribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE)?.name,
        )
        assertEquals(
            currentGradleArchitecture(),
            runtime.attributes.getAttribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE)?.name,
        )
    }
}
