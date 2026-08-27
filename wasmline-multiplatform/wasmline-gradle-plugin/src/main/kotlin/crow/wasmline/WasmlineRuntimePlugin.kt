package crow.wasmline

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.nativeplatform.MachineArchitecture
import org.gradle.nativeplatform.OperatingSystemFamily

/**
 * Selects the native Wasmline engine artifact for the current JVM host.
 *
 * Date: 2026-08-26
 * Author: crowforkotlin
 */
public class WasmlineRuntimePlugin : Plugin<Project> {
    public override fun apply(target: Project) {
        val operatingSystem = target.objects.named(OperatingSystemFamily::class.java, currentGradleOperatingSystem())
        val architecture = target.objects.named(MachineArchitecture::class.java, currentGradleArchitecture())

        target.configurations.configureEach { configuration ->
            if (configuration.isCanBeResolved) {
                configuration.attributes.attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, operatingSystem)
                configuration.attributes.attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, architecture)
            }
        }
    }
}

/** Returns the Gradle operating-system attribute for the current JVM. */
internal fun currentGradleOperatingSystem(): String =
    gradleOperatingSystem(requireNotNull(System.getProperty("os.name")) { "The JVM did not report os.name" })

/** Returns the Gradle machine-architecture attribute for the current JVM. */
internal fun currentGradleArchitecture(): String =
    gradleArchitecture(requireNotNull(System.getProperty("os.arch")) { "The JVM did not report os.arch" })

/** Converts a JVM operating-system name to a Gradle native attribute. */
internal fun gradleOperatingSystem(osName: String): String = when {
    osName.contains("linux", ignoreCase = true) -> OperatingSystemFamily.LINUX

    osName.contains("mac", ignoreCase = true) || osName.contains("darwin", ignoreCase = true) ->
        OperatingSystemFamily.MACOS

    osName.contains("windows", ignoreCase = true) -> OperatingSystemFamily.WINDOWS

    else -> error("Unsupported JVM operating system for a Wasmline native engine: $osName")
}

/** Converts a JVM architecture name to a Gradle native attribute. */
internal fun gradleArchitecture(architecture: String): String = when (architecture.lowercase()) {
    "amd64", "x86_64", "x64" -> MachineArchitecture.X86_64
    "aarch64", "arm64" -> MachineArchitecture.ARM64
    else -> error("Unsupported JVM architecture for a Wasmline native engine: $architecture")
}
