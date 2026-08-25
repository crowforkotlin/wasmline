package crow.wasmline

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WasmlineComponentBrowserBoundaryTest {
    @Test
    fun rejectsComponentArtifactsBeforeBrowserArtifactResolution() {
        val state = platformWasmlineLoadArtifact(
            WasmlineArtifactDescriptor(
                path = "/not-prefetched/component.wasm",
                artifactFormat = WasmlineArtifactFormat.RAW_WASM,
                executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
                invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
            ),
            WasmlineConfig(),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(state)
        assertTrue(
            failure.failure.message.contains("Browser host supports CORE_WASM with WASMLINE_SERVICE or RAW_EXPORT"),
            failure.failure.message,
        )
        assertTrue(!failure.failure.message.contains("prefetch", ignoreCase = true), failure.failure.message)
    }

    @Test
    fun typedComponentInstanceApiFailsFastOnBrowser() {
        val handle = Wasmline(
            moduleKey = "browser-component-boundary",
            config = WasmlineConfig(),
            descriptor = WasmlineArtifactDescriptor(
                path = "/unused/component.wasm",
                artifactFormat = WasmlineArtifactFormat.RAW_WASM,
                executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
                invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
            ),
        )

        val failure = runCatching { handle.component().instantiate() }.exceptionOrNull()
        assertIs<UnsupportedOperationException>(failure)
        assertTrue(failure.message.orEmpty().contains("does not support Component Model instances"))
        handle.close()
    }
}
