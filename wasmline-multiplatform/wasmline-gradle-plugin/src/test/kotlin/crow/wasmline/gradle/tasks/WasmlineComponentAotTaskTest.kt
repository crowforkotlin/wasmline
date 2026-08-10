package crow.wasmline.gradle.tasks

import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.loader.model.WasmlineArtifactType
import crow.wasmline.plugin.core.component.ComponentAotBuildRecords
import crow.wasmline.plugin.core.component.ComponentBuildRecords
import crow.wasmline.plugin.core.component.ComponentizeResult
import crow.wasmline.plugin.core.toolchain.FileDigest
import org.gradle.api.tasks.CacheableTask
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WasmlineComponentAotTaskTest {
    @Test
    fun compilesRawRecordToCwasmAndPwasm() = withTaskDirectory { root ->
        if (isWindows()) return@withTaskDirectory
        val componentDirectory = File(root, "component").apply { mkdirs() }
        val component = File(componentDirectory, "plugin-component.wasm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val componentResult = ComponentizeResult(
            coreWasm = component,
            embeddedWasm = component,
            componentWasm = component,
            inspectedWit = null,
            world = "plugin",
            exportName = "plugin/invoke",
            codec = "protobuf",
            rpcProtocolVersion = "1",
            componentSha256 = FileDigest.sha256Hex(component),
            witSha256 = "a".repeat(64),
            adapterSha256 = null,
            adapterVersion = null,
            witBindgenVersion = "0.57.1",
            wasmToolsVersion = "1.255.0",
        )
        val componentRecord = File(componentDirectory, ComponentBuildRecords.FILE_NAME)
        ComponentBuildRecords.write(componentResult, componentRecord)
        val compiler = fakeWasmtimeCompiler(File(root, "wasmtime"))
        val output = File(root, "aot")
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val task = project.tasks.register("componentAot", WasmlineComponentAotTask::class.java).get().apply {
            this.componentDirectory.set(componentDirectory)
            componentRecordFile.set(componentRecord)
            wasmtimeCompilerExecutable.set(compiler)
            wasmtimeVersion.set("v47.0.2")
            targets.set(listOf("x86_64-linux", "pulley64"))
            productName.set("plugin")
            outputDirectory.set(output)
        }

        task.compileComponentAot()

        val record = ComponentAotBuildRecords.read(File(output, ComponentAotBuildRecords.FILE_NAME))
        assertEquals(listOf(WasmlineArtifactType.CWASM, WasmlineArtifactType.PWASM), record.artifacts.map { it.type })
        assertTrue(record.artifacts.all { it.executionModel == WasmlineExecutionModel.COMPONENT_MODEL })
        assertTrue(record.resolveArtifacts(output).all { it.file.isFile })
    }

    @Test
    fun taskDeclaresCacheableInputsAndOutputs() {
        assertTrue(WasmlineComponentAotTask::class.java.isAnnotationPresent(CacheableTask::class.java))
    }

    private fun fakeWasmtimeCompiler(file: File): File = file.apply {
        writeText(
            """
            |#!/bin/sh
            |if [ "${'$'}1" = "--version" ]; then
            |  echo "wasmtime 47.0.2"
            |  exit 0
            |fi
            |if [ "${'$'}1" = "compile" ] && [ "${'$'}2" = "--help" ]; then
            |  exit 0
            |fi
            |previous=""
            |for argument in "${'$'}@"; do
            |  if [ "${'$'}previous" = "-o" ]; then
            |    printf '\001\002\003' > "${'$'}argument"
            |    exit 0
            |  fi
            |  previous="${'$'}argument"
            |done
            |exit 9
            """.trimMargin(),
        )
        check(setExecutable(true)) { "Unable to make fake Wasmtime executable: $absolutePath" }
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
}

private inline fun withTaskDirectory(block: (File) -> Unit) {
    val directory = createTempDirectory("wasmline-component-aot-task-test").toFile()
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}
