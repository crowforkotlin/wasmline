package crow.wasmline.test.wasmtime

import crow.wasmline.Wasmline
import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineEngineKind
import crow.wasmline.WasmlineLoadResult
import crow.wasmline.WasmlineRuntime
import crow.wasmline.loader.WasmlineLoadOptions
import crow.wasmline.loader.WasmlineLoader
import crow.wasmline.loader.WasmlineTrustedKeySet
import kotlinx.coroutines.runBlocking
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
    private const val MANIFEST_PROPERTY = "wasmline.plugin.manifest.path"

    /**
     * Loads the assembled plugin, executes the test block, and releases native resources.
     */
    fun <T> withLoadedPlugin(supportConcurrent: Boolean = false, block: (Wasmline) -> T): T = runBlocking {
        val manifest = manifestFile()
        WasmlineRuntime.preload()
        WasmlineRuntime.warmUp(WasmlineEngineKind.CRANELIFT)
        try {
            val result = WasmlineLoader.load(
                source = manifest.absolutePath,
                options = WasmlineLoadOptions(
                    runtimeConfig = WasmlineConfig(supportConcurrent = supportConcurrent),
                    trustedKeys = WasmlineTrustedKeySet.Builder()
                        .addHex(
                            algorithm = "Ed25519",
                            keyId = null,
                            publicKeyHex = "5a778289bee0c57b05a1c48c8ef312da6ce8e4e4f13fc1a2e8e5aa4cde7ae0db",
                        )
                        .build(),
                ),
            )
            val wasmline = assertIs<WasmlineLoadResult.Success>(
                value = result,
                message = (result as? WasmlineLoadResult.Failure)?.failure?.message,
            ).wasmline
            try {
                block(wasmline)
            } finally {
                wasmline.close()
            }
        } finally {
            WasmlineRuntime.shutdown()
        }
    }

    /**
     * Resolves the native artifact assembled for the current test run.
     */
    fun manifestFile(): File {
        val path = requireNotNull(System.getProperty(MANIFEST_PROPERTY)) {
            "Missing $MANIFEST_PROPERTY system property."
        }
        return File(path).absoluteFile.also { file ->
            assertTrue(file.isFile, "Manifest not found: ${file.path}.")
            assertTrue(file.length() > 0, "Manifest is empty: ${file.path}.")
        }
    }
}
