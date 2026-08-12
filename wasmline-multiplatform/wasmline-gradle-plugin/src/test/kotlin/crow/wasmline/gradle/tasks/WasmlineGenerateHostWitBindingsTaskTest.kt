package crow.wasmline.gradle.tasks

import org.gradle.api.tasks.CacheableTask
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WasmlineGenerateHostWitBindingsTaskTest {
    @Test
    fun generatesHostFacadeAsIndependentCacheableOutput() = withTaskDirectory { root ->
        val wit = File(root, "wit").apply { mkdirs() }
        File(wit, "world.wit").writeText(
            """
            package test:host@1.0.0;
            interface echo { invoke: func(value: string) -> string; }
            world plugin { export echo; }
            """.trimIndent(),
        )
        val output = File(root, "generated-host")
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val task = project.tasks.register("generateHost", WasmlineGenerateHostWitBindingsTask::class.java).get().apply {
            witDirectory.set(wit)
            outputDirectory.set(output)
            world.set("plugin")
            kotlinPackage.set("generated.host")
            resourceSupport.set(false)
        }

        task.generate()

        val generated = File(output, "generated/host/PluginWorld.kt")
        assertTrue(generated.isFile)
        assertTrue(generated.readText().contains("interface EchoClient"))
        assertEquals("1", task.generatorVersion)
        assertTrue(WasmlineGenerateHostWitBindingsTask::class.java.isAnnotationPresent(CacheableTask::class.java))
    }
}

private inline fun withTaskDirectory(block: (File) -> Unit) {
    val directory = createTempDirectory("wasmline-host-wit-task-test").toFile()
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}
