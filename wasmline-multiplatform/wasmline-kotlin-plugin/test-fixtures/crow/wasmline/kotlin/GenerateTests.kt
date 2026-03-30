package crow.wasmline.kotlin

import crow.wasmline.kotlin.runners.AbstractJvmBoxTest
import crow.wasmline.kotlin.runners.AbstractJvmDiagnosticTest
import org.jetbrains.kotlin.generators.dsl.junit5.generateTestGroupSuiteWithJUnit5
import java.nio.file.Files
import java.nio.file.Path

fun main() {
    val moduleDir = resolveModuleDir()
    val testsRootDir = System.getProperty("wasmline.kotlin.plugin.testsRootDir")
        ?.takeIf { it.isNotBlank() }
        ?.let { Path.of(it).toAbsolutePath().normalize() }
        ?: moduleDir.resolve("test-gen")
    generateTestGroupSuiteWithJUnit5 {
        testGroup(
            testDataRoot = moduleDir.resolve("testData").toString(),
            testsRoot = testsRootDir.toString(),
        ) {
            testClass<AbstractJvmBoxTest> {
                model("box")
            }
            testClass<AbstractJvmDiagnosticTest> {
                model("diagnostics")
            }
        }
    }
}

private fun resolveModuleDir(): Path {
    System.getProperty("wasmline.kotlin.plugin.projectDir")
        ?.takeIf { it.isNotBlank() }
        ?.let { Path.of(it).toAbsolutePath().normalize() }
        ?.let { return it }

    val start = Path.of("").toAbsolutePath().normalize()
    for (dir in generateSequence(start) { it.parent }) {
        if (dir.fileName?.toString() == "wasmline-kotlin-plugin" && Files.exists(dir.resolve("build.gradle.kts"))) {
            return dir
        }

        val directChild = dir.resolve("wasmline-kotlin-plugin")
        if (Files.exists(directChild.resolve("build.gradle.kts"))) {
            return directChild
        }

        val nestedChild = dir.resolve("wasmline-multiplatform/wasmline-kotlin-plugin")
        if (Files.exists(nestedChild.resolve("build.gradle.kts"))) {
            return nestedChild
        }
    }

    error(
        "Unable to locate wasmline-kotlin-plugin directory. " +
            "Set -Dwasmline.kotlin.plugin.projectDir=/absolute/path/to/wasmline-kotlin-plugin if needed.",
    )
}

