package crow.wasmline.cli

import com.github.ajalt.clikt.core.parse
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.WasmlineArtifactType
import crow.wasmline.plugin.core.compiler.WasmtimeCompiler
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoreCliRegressionTest {
    @Test
    fun `core compile still accepts runtime wasmtime and preserves core metadata`() = withCoreCliDirectory { root ->
        if (System.getProperty("os.name").contains("win", ignoreCase = true)) return@withCoreCliDirectory

        val input = File(root, "plugin.wasm").apply { writeBytes(byteArrayOf(0, 97, 115, 109)) }
        val wasmtimeDirectory = File(root, "wasmtime").apply { mkdirs() }
        File(wasmtimeDirectory, "wasmtime-min").apply {
            writeText(
                """#!/bin/sh
                    |output=
                    |while [ "${'$'}#" -gt 0 ]; do
                    |  if [ "${'$'}1" = "-o" ]; then
                    |    shift
                    |    output="${'$'}1"
                    |  fi
                    |  shift
                    |done
                    |printf 'compiled-core' > "${'$'}output"
                    |
                """.trimMargin(),
            )
            assertTrue(setExecutable(true))
        }
        val outputRoot = File(root, "output")

        Compile().parse(
            listOf(
                "--input",
                input.absolutePath,
                "--name",
                "core-plugin",
                "--output",
                outputRoot.absolutePath,
                "--wasmtime",
                wasmtimeDirectory.absolutePath,
                "--arch",
                "pulley64",
            ),
        )

        val resultFile = File(outputRoot, "core-plugin-1.0.0/debug/${WasmtimeCompiler.COMPILE_RESULT_FILE}")
        val result = WasmtimeCompiler().readCompileResult(resultFile)
        val nativeArtifact = result.artifacts.single { it.type == WasmlineArtifactType.PWASM }
        assertEquals("wasmtime-${BuildConfig.WASMTIME_VERSION}", nativeArtifact.targetCompilerVersion)
        assertEquals("pulley64", nativeArtifact.targetCpu)
        assertEquals(null, nativeArtifact.targetOs)
        assertEquals(WasmlineExecutionModel.CORE_WASM, nativeArtifact.executionModel)
        assertEquals(WasmlineInvocationProtocol.WASMLINE_CORE, nativeArtifact.invocationProtocol)
        assertTrue(result.artifacts.all { it.executionModel == WasmlineExecutionModel.CORE_WASM })
        assertTrue(result.artifacts.any { it.type == WasmlineArtifactType.WASM && it.targetOs == "browser" })
    }
}

private inline fun withCoreCliDirectory(block: (File) -> Unit) {
    val directory = createTempDirectory("wasmline-cli-core-regression-test").toFile()
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}
