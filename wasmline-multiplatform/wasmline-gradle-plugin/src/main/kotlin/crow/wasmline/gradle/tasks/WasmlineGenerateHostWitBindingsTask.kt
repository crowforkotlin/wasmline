package crow.wasmline.gradle.tasks

import crow.wasmline.plugin.core.component.hostgen.KotlinHostBindingsGenerator
import crow.wasmline.plugin.core.component.hostgen.KotlinHostBindingsRequest
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Generates JVM/Native Host facades from WIT without invoking the Kotlin compiler plugin. */
@CacheableTask
abstract class WasmlineGenerateHostWitBindingsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val witDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val world: Property<String>

    @get:Input
    abstract val kotlinPackage: Property<String>

    @get:Input
    abstract val resourceSupport: Property<Boolean>

    @get:Input
    val generatorVersion: String = KotlinHostBindingsGenerator.VERSION

    @TaskAction
    fun generate() {
        val output = outputDirectory.get().asFile
        project.delete(output)
        KotlinHostBindingsGenerator.generate(
            KotlinHostBindingsRequest(
                witPath = witDirectory.get().asFile,
                outputDirectory = output,
                world = world.get(),
                kotlinPackage = kotlinPackage.get(),
                allowResources = resourceSupport.get(),
            ),
        )
    }
}
