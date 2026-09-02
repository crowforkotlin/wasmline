package crow.wasmline.test.wasmtime

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineEngineKind
import crow.wasmline.platformWasmlineRuntimeCapabilities
import crow.wasmline.test.fixtures.NativeFixtureCatalog
import java.io.File

/**
 * Provides generated native AOT artifacts to JVM integration tests.
 *
 * Date: 2026-09-01
 * Author: crowforkotlin
 */
internal object NativeFixtureTestSupport {
    private val catalog: NativeFixtureCatalog by lazy(NativeFixtureCatalog::fromSystemProperty)

    /** Copies one generated fixture into a temporary file for a test invocation. */
    fun copy(fixtureId: String, format: WasmlineArtifactFormat = WasmlineArtifactFormat.CWASM): File {
        val runtime = platformWasmlineRuntimeCapabilities()
        val backend = when (format) {
            WasmlineArtifactFormat.CWASM -> WasmlineEngineKind.CRANELIFT
            WasmlineArtifactFormat.PWASM -> WasmlineEngineKind.PULLEY
            WasmlineArtifactFormat.RAW_WASM -> error("Native fixture artifacts cannot use raw Wasm.")
        }
        val profileId = runtime.aotCompatibilityProfileIdsByBackend[backend]
            ?.singleOrNull()
            ?: error("Native runtime must report exactly one $backend AOT compatibility profile for test fixtures.")
        val requestedTarget = when (format) {
            WasmlineArtifactFormat.CWASM -> "${runtime.architecture}-${runtime.operatingSystem}"
            WasmlineArtifactFormat.PWASM -> "pulley${runtime.pointerWidth}"
            WasmlineArtifactFormat.RAW_WASM -> error("Native fixture artifacts cannot use raw Wasm.")
        }
        return catalog.copyArtifact(fixtureId, format, requestedTarget, profileId)
    }
}
