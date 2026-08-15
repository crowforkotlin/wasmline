package crow.wasmline.gradle.tasks

import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlinePlugin
import crow.wasmline.gradle.WasmtimeTarget
import crow.wasmline.gradle.extensions.WasmlineExtension
import crow.wasmline.plugin.core.download.WasmtimeDistribution
import crow.wasmline.plugin.core.toolchain.ToolchainCatalog
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalWasmDsl::class)
class WasmlineComponentAotRegistrationTest {
    @Test
    fun registersFullCompilerDownloadAndVariantAotTasks() = withRegistrationDirectory { root ->
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        project.pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        project.pluginManager.apply(WasmlinePlugin::class.java)
        val extension = project.extensions.getByType(WasmlineExtension::class.java)
        val explicitCompiler = File(root, "tools/wasmtime").apply {
            parentFile.mkdirs()
            writeText("full compiler")
        }
        extension.manifest.pluginId.set("crow.test.plugin")
        extension.wasmtime.compilerExecutable.set(explicitCompiler)
        extension.wasmtime.targets = listOf(WasmtimeTarget.PULLEY_64, WasmtimeTarget.AARCH64_LINUX)
        project.extensions.getByType(KotlinMultiplatformExtension::class.java).wasmWasi()

        val compilerDownload = project.tasks.named(
            "wasmlineDownloadWasmtimeCompiler",
            DownloadWasmtimeTask::class.java,
        ).get()
        val minimalDownload = project.tasks.named(
            "wasmlineDownloadWasmtime",
            DownloadWasmtimeTask::class.java,
        ).get()
        val debugAot = project.tasks.named("wasmlineComponentAotDebug", WasmlineComponentAotTask::class.java).get()
        val releaseAot = project.tasks.named("wasmlineComponentAotRelease", WasmlineComponentAotTask::class.java).get()

        assertEquals(WasmtimeDistribution.FULL, compilerDownload.distribution.get())
        assertEquals(WasmtimeDistribution.MINIMAL, minimalDownload.distribution.get())
        assertEquals(ToolchainCatalog.WASMTIME_VERSION, debugAot.wasmtimeVersion.get())
        assertEquals(explicitCompiler.canonicalFile, debugAot.wasmtimeCompilerExecutable.get().asFile.canonicalFile)
        assertEquals(listOf("pulley64", "aarch64-linux"), debugAot.targets.get())
        assertTrue(debugAot.outputDirectory.get().asFile.invariantSeparatorsPath.endsWith("wasmline/component-aot/debug"))
        assertTrue(releaseAot.outputDirectory.get().asFile.invariantSeparatorsPath.endsWith("wasmline/component-aot/release"))
        assertTrue(debugAot.taskDependencies.getDependencies(debugAot).any { it.name == "wasmlineComponentizeDebug" })
        assertTrue(releaseAot.taskDependencies.getDependencies(releaseAot).any { it.name == "wasmlineComponentizeRelease" })
    }

    @Test
    fun autoDownloadAddsOnlyTheFullCompilerDownloadDependency() = withRegistrationDirectory { root ->
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        project.pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        project.pluginManager.apply(WasmlinePlugin::class.java)
        val extension = project.extensions.getByType(WasmlineExtension::class.java)
        extension.wasmtime.autoDownload.set(true)
        project.extensions.getByType(KotlinMultiplatformExtension::class.java).wasmWasi()
        val aotTask = project.tasks.named("wasmlineComponentAotDebug", WasmlineComponentAotTask::class.java).get()
        val dependencyNames = aotTask.taskDependencies.getDependencies(aotTask).map { it.name }

        assertTrue("wasmlineDownloadWasmtimeCompiler" in dependencyNames)
        assertTrue("wasmlineDownloadWasmtime" !in dependencyNames)
    }

    @Test
    fun componentAssembleDependsDirectlyOnAotAndReadsItsOutput() = withRegistrationDirectory { root ->
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        project.pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        project.pluginManager.apply(WasmlinePlugin::class.java)
        val extension = project.extensions.getByType(WasmlineExtension::class.java)
        extension.manifest.executionModel.set(WasmlineExecutionModel.COMPONENT_MODEL)
        extension.wasmtime.compilerExecutable.set(File(root, "wasmtime").apply { writeText("full compiler") })
        project.extensions.getByType(KotlinMultiplatformExtension::class.java).wasmWasi()
        WasmlinePlugin().configureAssembleTaskGraph(project, extension)

        val assemble = project.tasks.named("wasmlineAssembleDebug", WasmlineAssembleTask::class.java).get()
        val aot = project.tasks.named("wasmlineComponentAotDebug", WasmlineComponentAotTask::class.java).get()
        val assembleDependencies = assemble.taskDependencies.getDependencies(assemble).map { it.name }
        val aotDependencies = aot.taskDependencies.getDependencies(aot).map { it.name }

        assertTrue("wasmlineComponentAotDebug" in assembleDependencies)
        assertTrue("wasmlineComponentizeDebug" !in assembleDependencies)
        assertTrue("wasmlineComponentizeDebug" in aotDependencies)
        assertEquals(aot.outputDirectory.get().asFile.canonicalFile, assemble.componentOutputDirectory.get().asFile.canonicalFile)
        assertTrue(!assemble.wasmCompileOutputDir.isPresent)
    }

    @Test
    fun coreAssembleKeepsItsKotlinCompileDependencyAndNoComponentInput() = withRegistrationDirectory { root ->
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        project.pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        project.pluginManager.apply(WasmlinePlugin::class.java)
        val extension = project.extensions.getByType(WasmlineExtension::class.java)
        extension.wasmtime.targets = listOf(WasmtimeTarget.PULLEY_32, WasmtimeTarget.X86_64_WINDOWS)
        project.extensions.getByType(KotlinMultiplatformExtension::class.java).wasmWasi()
        project.tasks.register("compileDevelopmentLibraryKotlinWasmWasiOptimize")
        project.tasks.register("compileProductionLibraryKotlinWasmWasiOptimize")
        WasmlinePlugin().configureAssembleTaskGraph(project, extension)

        val assemble = project.tasks.named("wasmlineAssembleDebug", WasmlineAssembleTask::class.java).get()
        val dependencyNames = assemble.taskDependencies.getDependencies(assemble).map { it.name }

        assertEquals(WasmlineExecutionModel.CORE_WASM, assemble.executionModel.get())
        assertEquals(listOf("pulley32", "x86_64-windows"), assemble.compileTargets.get())
        assertTrue("compileDevelopmentLibraryKotlinWasmWasiOptimize" in dependencyNames)
        assertTrue(dependencyNames.none { it.startsWith("wasmlineComponent") })
        assertTrue(assemble.wasmCompileOutputDir.isPresent)
        assertTrue(!assemble.componentOutputDirectory.isPresent)
    }
}

private inline fun withRegistrationDirectory(block: (File) -> Unit) {
    val directory = createTempDirectory("wasmline-component-aot-registration-test").toFile()
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}
