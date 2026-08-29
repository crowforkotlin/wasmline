package crow.wasmline.gradle.tasks

import crow.wasmline.RawAbiMetadata
import crow.wasmline.RawExport
import crow.wasmline.RawExportKind
import crow.wasmline.RawFunctionSignature
import crow.wasmline.RawValueType
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.WasmlinePlugin
import crow.wasmline.gradle.WasmtimeTarget
import crow.wasmline.gradle.extensions.WasmlineExtension
import crow.wasmline.plugin.core.aot.WasmlineRawAbiMetadataCodec
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies unified AOT task registration and dependency wiring for Core and Component builds.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
@OptIn(ExperimentalWasmDsl::class)
class WasmlineAotBuildRegistrationTest {
    @Test
    fun registersCatalogBackedAotTasksAndPropagatesSelectors() = withRegistrationDirectory { root ->
        val (project, extension) = wasmWasiProject(root)
        extension.manifest.pluginId.set("crow.test.plugin")
        extension.manifest.invocationProtocol.set(WasmlineInvocationProtocol.RAW_EXPORT)
        extension.wasmtime.aotCompatibility.current()
        extension.wasmtime.aotCompatibility.suppressCompatibilityWarning.set(true)
        extension.wasmtime.targets = listOf(WasmtimeTarget.PULLEY_64, WasmtimeTarget.AARCH64_LINUX)
        extension.wasmtime.autoDownload.set(true)
        extension.wasmtime.compilerCacheDirectory.set(File(root, "compiler-cache"))
        extension.wasmtime.maxParallelCompilations.set(3)
        val rawAbi = RawAbiMetadata(
            exports = listOf(
                RawExport(
                    "add_i32",
                    RawExportKind.FUNCTION,
                    RawFunctionSignature(listOf(RawValueType.I32, RawValueType.I32), listOf(RawValueType.I32)),
                ),
            ),
        )
        extension.manifest.rawAbi.set(rawAbi)

        val debugAot = project.tasks.named("wasmlineAotBuildDebug", WasmlineAotBuildTask::class.java).get()
        val releaseAot = project.tasks.named("wasmlineAotBuildRelease", WasmlineAotBuildTask::class.java).get()

        assertEquals("current", debugAot.aotCompatibilitySelector.get())
        assertEquals(emptyList(), debugAot.aotCompatibilityRanges.get())
        assertEquals(listOf("pulley64", "aarch64-linux"), debugAot.targets.get())
        assertTrue(debugAot.autoDownload.get())
        assertEquals(3, debugAot.maxParallelCompilations.get())
        assertEquals(File(root, "compiler-cache").canonicalFile, debugAot.compilerCacheDirectory.get().asFile.canonicalFile)
        assertEquals(rawAbi, WasmlineRawAbiMetadataCodec.decode(debugAot.rawAbiMetadataJson.get()))
        assertTrue(debugAot.outputDirectory.get().asFile.invariantSeparatorsPath.endsWith("wasmline/aot/debug"))
        assertTrue(releaseAot.outputDirectory.get().asFile.invariantSeparatorsPath.endsWith("wasmline/aot/release"))
        assertTrue(project.tasks.names.none { it.startsWith("wasmlineDownloadWasmtime") })
        assertTrue(project.tasks.names.none { it.startsWith("wasmlineComponentAot") })
        assertTrue(project.tasks.names.contains("wasmlineCheckAotCompatibility"))
        assertTrue(project.tasks.names.contains("wasmlineMarkAssembleDebug"))
        assertTrue(project.tasks.names.contains("wasmlineMarkAssembleRelease"))
        assertTrue(extension.wasmtime.aotCompatibility.suppressCompatibilityWarning.get())

        val checker = project.tasks.named("wasmlineCheckAotCompatibility", WasmlineCheckAotCompatibilityTask::class.java).get()
        val checkerDependencies = checker.taskDependencies.getDependencies(checker).map { it.name }
        assertFalse("wasmlineAotBuildDebug" in checkerDependencies)
        assertFalse("wasmlineAotBuildRelease" in checkerDependencies)

        val debugAssemble = project.tasks.named("wasmlineAssembleDebug", WasmlineAssembleTask::class.java).get()
        val releaseAssemble = project.tasks.named("wasmlineAssembleRelease", WasmlineAssembleTask::class.java).get()
        assertTrue(
            "wasmlineMarkAssembleDebug" in debugAssemble.taskDependencies.getDependencies(debugAssemble).map { it.name },
        )
        assertTrue(
            "wasmlineMarkAssembleRelease" in releaseAssemble.taskDependencies.getDependencies(releaseAssemble).map { it.name },
        )
        assertEquals(
            setOf("wasmlineCheckAotCompatibility"),
            debugAssemble.finalizedBy.getDependencies(debugAssemble).map { it.name }.toSet(),
        )
        assertEquals(
            setOf("wasmlineCheckAotCompatibility"),
            releaseAssemble.finalizedBy.getDependencies(releaseAssemble).map { it.name }.toSet(),
        )
    }

    @Test
    fun componentAssembleDependsOnUnifiedAotTask() = withRegistrationDirectory { root ->
        val (project, extension) = wasmWasiProject(root)
        extension.manifest.executionModel.set(WasmlineExecutionModel.COMPONENT_MODEL)
        WasmlinePlugin().configureAssembleTaskGraph(project, extension)

        val assemble = project.tasks.named("wasmlineAssembleDebug", WasmlineAssembleTask::class.java).get()
        val aot = project.tasks.named("wasmlineAotBuildDebug", WasmlineAotBuildTask::class.java).get()
        val assembleDependencies = assemble.taskDependencies.getDependencies(assemble).map { it.name }
        val aotDependencies = aot.taskDependencies.getDependencies(aot).map { it.name }

        assertTrue("wasmlineAotBuildDebug" in assembleDependencies)
        assertFalse("wasmlineComponentizeDebug" in assembleDependencies)
        assertTrue("wasmlineComponentizeDebug" in aotDependencies)
        assertTrue(aot.componentOutputDirectory.isPresent)
        assertFalse(aot.coreWasmCompileOutputDirectory.isPresent)
        assertEquals(aot.outputDirectory.get().asFile.canonicalFile, assemble.aotOutputDirectory.get().asFile.canonicalFile)
        assertTrue(
            assemble.finalizedBy.getDependencies(assemble).any {
                it.name == "wasmlineCheckAotCompatibility"
            },
        )
    }

    @Test
    fun coreAssembleDependsOnUnifiedAotTaskAndKotlinCompilation() = withRegistrationDirectory { root ->
        val (project, extension) = wasmWasiProject(root)
        extension.wasmtime.targets = listOf(WasmtimeTarget.PULLEY_32, WasmtimeTarget.X86_64_WINDOWS)
        project.tasks.register("compileDevelopmentLibraryKotlinWasmWasiOptimize")
        project.tasks.register("compileProductionLibraryKotlinWasmWasiOptimize")
        WasmlinePlugin().configureAssembleTaskGraph(project, extension)

        val assemble = project.tasks.named("wasmlineAssembleDebug", WasmlineAssembleTask::class.java).get()
        val aot = project.tasks.named("wasmlineAotBuildDebug", WasmlineAotBuildTask::class.java).get()
        val assembleDependencies = assemble.taskDependencies.getDependencies(assemble).map { it.name }
        val aotDependencies = aot.taskDependencies.getDependencies(aot).map { it.name }

        assertEquals(WasmlineExecutionModel.CORE_WASM, aot.executionModel.get())
        assertEquals(listOf("pulley32", "x86_64-windows"), aot.targets.get())
        assertTrue("wasmlineAotBuildDebug" in assembleDependencies)
        assertTrue("compileDevelopmentLibraryKotlinWasmWasiOptimize" in aotDependencies)
        assertTrue(aot.coreWasmCompileOutputDirectory.isPresent)
        assertFalse(aot.componentOutputDirectory.isPresent)
        assertTrue(
            assemble.finalizedBy.getDependencies(assemble).any {
                it.name == "wasmlineCheckAotCompatibility"
            },
        )
    }

    private fun wasmWasiProject(root: File): Pair<org.gradle.api.Project, WasmlineExtension> {
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        project.pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        project.pluginManager.apply(WasmlinePlugin::class.java)
        val extension = project.extensions.getByType(WasmlineExtension::class.java)
        project.extensions.getByType(KotlinMultiplatformExtension::class.java).wasmWasi()
        return project to extension
    }
}

private inline fun withRegistrationDirectory(block: (File) -> Unit) {
    val directory = createTempDirectory("wasmline-aot-registration-test").toFile()
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}
