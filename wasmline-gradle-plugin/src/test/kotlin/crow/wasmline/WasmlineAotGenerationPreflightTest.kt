package crow.wasmline

import crow.wasmline.plugin.core.aot.AotCompatibilityCatalog
import crow.wasmline.plugin.core.manifest.ManifestKeyGenerator
import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WasmlineAotGenerationPreflightTest {
    @Test
    fun rejectsOutOfCatalogRangeBeforeToolsOrPublication() {
        val directory = Files.createTempDirectory("wasmline-aot-preflight-").toFile()
        try {
            val catalog = AotCompatibilityCatalog.releaseCatalog()
            val minimum = catalog.minimumSupportedWasmlineVersion
            val current = catalog.currentWasmlineVersion
            val parts = current.split('.')
            val upper = "${parts[0]}.${parts[1]}.${parts[2].toBigInteger() + BigInteger.ONE}"
            directory.resolve("settings.gradle").writeText("rootProject.name = 'aot-preflight-consumer'")
            directory.resolve("wit").mkdirs()
            directory.resolve("wit/plugin.wit").writeText(
                "package test:preflight; world plugin { export run: func(); }",
            )
            directory.resolve("keys").mkdirs()
            directory.resolve("keys/private.key").writeText(ManifestKeyGenerator.generate().privateKeyHex)
            directory.resolve("build/compileSync/wasmWasi/main/productionLibrary/optimized").mkdirs()
            directory.resolve("build/compileSync/wasmWasi/main/productionLibrary/optimized/plugin.wasm")
                .writeBytes(byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00))

            val previousFiles = mapOf(
                "build/wasmline/output/crow.wasmline.preflight-1.0.0/manifest.wlm" to "previous manifest",
                "build/wasmline/output/crow.wasmline.preflight-1.0.0/artifacts/sha256/aa/previous.pwasm" to "previous artifact",
                "build/wasmline/aot/release/aot-build-record.json" to "previous build record",
                "build/wasmline/dist/crow.wasmline.preflight-1.0.0.zip" to "previous zip",
                "compiler-cache/retained/wasmtime" to "previous compiler cache",
                "component-cache/retained/wasm-tools" to "previous component cache",
            )
            previousFiles.forEach { (path, contents) ->
                directory.resolve(path).apply {
                    parentFile.mkdirs()
                    writeText(contents)
                }
            }
            val preservedDirectories = listOf("build/wasmline", "compiler-cache", "component-cache")
                .map(directory::resolve)
            val before = preservedDirectories.associateWith(::snapshot)
            directory.resolve("build.gradle").writeText(
                """
                plugins {
                    id 'org.jetbrains.kotlin.multiplatform'
                    id 'crow.wasmline'
                }
                kotlin {
                    wasmWasi {
                        nodejs()
                        binaries.library()
                    }
                }
                wasmline.manifest.pluginId.set('crow.wasmline.preflight')
                wasmline.manifest.version.set('1.0.0')
                wasmline.manifest.privateKeyFile.set(file('keys/private.key'))
                wasmline.manifest.executionModel.set(crow.wasmline.WasmlineExecutionModel.COMPONENT_MODEL)
                wasmline.manifest.invocationProtocol.set(crow.wasmline.WasmlineInvocationProtocol.COMPONENT_EXPORT)
                wasmline.manifest.exportName.set('run')
                wasmline.wasmtime.targets = [crow.wasmline.gradle.WasmtimeTarget.Companion.getPULLEY_64()]
                wasmline.wasmtime.aotCompatibility.generations('$minimum', '$upper')
                wasmline.wasmtime.autoDownload.set(true)
                wasmline.wasmtime.compilerCacheDirectory.set(layout.projectDirectory.dir('compiler-cache'))
                wasmline.component.autoDownload.set(true)
                wasmline.component.toolCacheDirectory.set(layout.projectDirectory.dir('component-cache'))
                // A preflight regression must not download tools or launch a compiler.
                tasks.configureEach { task ->
                    task.doFirst {
                        file('task-actions.log').append(task.path + '\n')
                        throw new GradleException('Unexpected task execution after invalid AOT selection')
                    }
                }
                """.trimIndent(),
            )

            val result = GradleRunner.create()
                .withProjectDir(directory)
                .withTestKitDir(directory.resolve("testkit"))
                .withPluginClasspath()
                .withArguments("wasmlineAssembleRelease", "--offline", "--stacktrace", "--no-configuration-cache")
                .buildAndFail()

            assertTrue(result.output.contains("[WLAOT100]"), result.output)
            assertTrue(result.output.contains(upper), result.output)
            assertTrue(result.output.contains("$minimum..$current"), result.output)
            assertFalse(directory.resolve("task-actions.log").exists(), result.output)
            assertEquals(before, preservedDirectories.associateWith(::snapshot))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun snapshot(directory: File): Map<String, List<Byte>> = directory.walkTopDown()
        .filter { it != directory }
        .associate { entry ->
            val path = entry.relativeTo(directory).invariantSeparatorsPath
            if (entry.isDirectory) "$path/" to emptyList() else path to entry.readBytes().toList()
        }
}
