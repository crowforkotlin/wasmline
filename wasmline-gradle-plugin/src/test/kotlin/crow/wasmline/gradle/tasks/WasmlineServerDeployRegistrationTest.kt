package crow.wasmline.gradle.tasks

import crow.wasmline.WasmlinePlugin
import crow.wasmline.gradle.WasmlineBuildVariant
import crow.wasmline.gradle.extensions.WasmlineExtension
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalWasmDsl::class)
class WasmlineServerDeployRegistrationTest {
    @Test
    fun defaultsToTheDebugPackage() = withServerProject { project, _ ->
        val server = project.tasks.named("wasmlineServerDeploy", WasmlineServerDeployTask::class.java).get()
        val dependencyNames = server.taskDependencies.getDependencies(server).map { it.name }

        assertTrue("wasmlineAssembleDebug" in dependencyNames)
        assertTrue("wasmlineAssembleRelease" !in dependencyNames)
    }

    @Test
    fun usesTypedConfigurationSetAfterTaskRegistration() = withServerProject { project, extension ->
        extension.manifest.pluginId.set("crow.test.server")
        extension.manifest.version.set("2.0.0")
        extension.server.deployVariant.set(WasmlineBuildVariant.RELEASE)

        val server = project.tasks.named("wasmlineServerDeploy", WasmlineServerDeployTask::class.java).get()
        val dependencyNames = server.taskDependencies.getDependencies(server).map { it.name }

        assertTrue("wasmlineAssembleRelease" in dependencyNames)
        assertTrue("wasmlineAssembleDebug" !in dependencyNames)
        assertTrue(
            server.serveDirectory.get().asFile.invariantSeparatorsPath
                .endsWith("build/wasmline/output/crow.test.server-2.0.0"),
        )
    }
}

private inline fun withServerProject(block: (Project, WasmlineExtension) -> Unit) {
    val directory = createTempDirectory("wasmline-server-registration-test").toFile()
    try {
        val project = ProjectBuilder.builder().withProjectDir(directory).build()
        project.pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        project.pluginManager.apply(WasmlinePlugin::class.java)
        val extension = project.extensions.getByType(WasmlineExtension::class.java)
        extension.manifest.pluginId.set("crow.test.default")
        project.extensions.getByType(KotlinMultiplatformExtension::class.java).wasmWasi()
        block(project, extension)
    } finally {
        directory.deleteRecursively()
    }
}
