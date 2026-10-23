package crow.wasmline

import org.gradle.nativeplatform.MachineArchitecture
import org.gradle.nativeplatform.OperatingSystemFamily
import org.gradle.testkit.runner.GradleRunner
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
    fun selectsHostEngineWithConsistentClasspathResolution() {
        val directory = Files.createTempDirectory("wasmline-engine-resolution-").toFile()
        try {
            val operatingSystem = currentGradleOperatingSystem()
            val architecture = currentGradleArchitecture()
            val module = directory.resolve("repository/example/engine/1.0").apply { mkdirs() }
            val variants = listOf(
                "host" to (operatingSystem to architecture),
                "other-os" to ((if (operatingSystem == "linux") "windows" else "linux") to architecture),
                "other-cpu" to (operatingSystem to if (architecture == "x86-64") "aarch64" else "x86-64"),
            ).flatMap { (name, platform) ->
                val artifact = "engine-1.0-$name.jar"
                module.resolve(artifact).writeText(name)
                listOf("java-api", "java-runtime").map { usage ->
                    """
                    {
                        "name": "$name-$usage",
                        "attributes": {
                            "org.gradle.category": "library",
                            "org.gradle.usage": "$usage",
                            "org.gradle.libraryelements": "jar",
                            "org.gradle.jvm.environment": "standard-jvm",
                            "org.gradle.native.operatingSystem": "${platform.first}",
                            "org.gradle.native.architecture": "${platform.second}"
                        },
                        "files": [{"name": "$artifact", "url": "$artifact"}]
                    }
                    """.trimIndent()
                }
            }
            module.resolve("engine-1.0.module").writeText(
                """
                {
                    "formatVersion": "1.1",
                    "component": {"group": "example", "module": "engine", "version": "1.0"},
                    "variants": [${variants.joinToString(",")}]
                }
                """.trimIndent(),
            )
            directory.resolve("settings.gradle").writeText("rootProject.name = 'engine-consumer'")
            directory.resolve("build.gradle").writeText(
                """
                plugins {
                    id 'java-library'
                    id 'crow.wasmline' apply false
                }
                apply plugin: crow.wasmline.WasmlineRuntimePlugin
                repositories {
                    maven {
                        url = uri('repository')
                        metadataSources { gradleMetadata() }
                    }
                }
                dependencies { implementation 'example:engine:1.0' }
                configurations.runtimeClasspath.shouldResolveConsistentlyWith(configurations.compileClasspath)
                tasks.register('resolveEngine') {
                    doLast {
                        def selected = configurations.runtimeClasspath.incoming.artifacts.artifactFiles.files
                        assert selected*.name == ['engine-1.0-host.jar']
                        assert configurations.runtimeClasspath.files == selected
                        println 'HOST ENGINE SELECTED'
                    }
                }
                """.trimIndent(),
            )
            val result = GradleRunner.create()
                .withProjectDir(directory)
                .withPluginClasspath()
                .withArguments("resolveEngine", "--offline", "--stacktrace")
                .build()
            assertTrue(result.output.contains("HOST ENGINE SELECTED"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
