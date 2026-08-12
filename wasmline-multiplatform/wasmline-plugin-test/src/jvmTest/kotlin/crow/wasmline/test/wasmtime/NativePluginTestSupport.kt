package crow.wasmline.test.wasmtime

import crow.wasmline.Wasmline
import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.WasmlineLoadResult
import crow.wasmline.WasmlineWarmupMode
import crow.wasmline.loader.WasmlineLoader
import crow.wasmline.wasmlineBootstrap
import crow.wasmline.wasmlineShutdown
import crow.wasmline.wasmlineWarmup
import java.io.File
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Provides shared setup for native plugin integration tests.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
internal object NativePluginTestSupport {
    private const val ARTIFACT_PROPERTY = "wasmline.plugin.artifact.path"

    /**
     * Loads the assembled plugin, executes the test block, and releases native resources.
     */
    fun <T> withLoadedPlugin(supportConcurrent: Boolean = false, block: (Wasmline) -> T): T {
        val artifact = artifactFile()
        wasmlineBootstrap()
        wasmlineWarmup(WasmlineWarmupMode.CRANELIFT)
        try {
            val result = WasmlineLoader.load(
                descriptor = WasmlineArtifactDescriptor(
                    path = artifact.absolutePath,
                    artifactFormat = WasmlineArtifactFormat.CWASM,
                    targetCpu = nativeTargetCpu(),
                    targetOs = nativeTargetOs(),
                    targetCompilerVersion = "wasmtime-47.0.2",
                    is64Bit = true,
                    executionModel = WasmlineExecutionModel.CORE_WASM,
                    invocationProtocol = WasmlineInvocationProtocol.WASMLINE_CORE,
                ),
                config = WasmlineConfig(supportConcurrent = supportConcurrent),
            )
            val wasmline = assertIs<WasmlineLoadResult.Success>(
                value = result,
                message = (result as? WasmlineLoadResult.Failure)?.cause,
            ).wasmline
            try {
                return block(wasmline)
            } finally {
                wasmline.close()
            }
        } finally {
            wasmlineShutdown()
        }
    }

    /**
     * Resolves the native artifact assembled for the current test run.
     */
    fun artifactFile(): File {
        val path = requireNotNull(System.getProperty(ARTIFACT_PROPERTY)) {
            "Missing $ARTIFACT_PROPERTY system property."
        }
        return File(path).absoluteFile.also { file ->
            assertTrue(file.isFile, "Artifact not found: ${file.path}.")
            assertTrue(file.length() > 0, "Artifact is empty: ${file.path}.")
        }
    }

    private fun nativeTargetCpu(): String = when (System.getProperty("os.arch").lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        else -> "x86_64"
    }

    private fun nativeTargetOs(): String = when {
        System.getProperty("os.name").contains("mac", ignoreCase = true) -> "macos"
        else -> "linux"
    }
}
