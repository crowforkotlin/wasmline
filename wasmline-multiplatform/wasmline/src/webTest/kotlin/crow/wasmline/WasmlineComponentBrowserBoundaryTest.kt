package crow.wasmline

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WasmlineComponentBrowserBoundaryTest {
    @Test
    fun rejectsComponentArtifactsBeforeBrowserArtifactResolution() {
        val state = wasmlineLoadArtifact(
            WasmlineArtifactDescriptor(
                path = "/not-prefetched/component.wasm",
                artifactFormat = WasmlineArtifactFormat.RAW_WASM,
                executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
                invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
            ),
            WasmlineConfig(),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(state)
        assertTrue(failure.cause.contains("Browser host supports only CORE_WASM with WASMLINE_CORE"), failure.cause)
        assertTrue(!failure.cause.contains("prefetch", ignoreCase = true), failure.cause)
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
