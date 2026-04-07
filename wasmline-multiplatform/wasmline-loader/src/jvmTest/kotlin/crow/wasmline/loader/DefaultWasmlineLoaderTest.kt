package crow.wasmline.loader

import crow.wasmline.WasmlineLoadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DefaultWasmlineLoaderTest {

    @Test
    fun `top level request entrypoint rejects local package source until package resolution is implemented`() {
        val result = loadWasmline(
            request = WasmlineLoadRequest(
                source = WasmlineSource.LocalPackageFile(path = "/tmp/plugin.wlm"),
            ),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertEquals(WasmlineLoadState.CODE_FAILURE, failure.code)
        assertTrue(failure.cause.contains("Local package source '/tmp/plugin.wlm'"))
    }

    @Test
    fun `local package source is rejected until package resolution is implemented`() {
        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.LocalPackageFile(path = "/tmp/plugin.wlm"),
            ),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertEquals(WasmlineLoadState.CODE_FAILURE, failure.code)
        assertTrue(failure.cause.contains("Local package source '/tmp/plugin.wlm'"))
        assertTrue(failure.cause.contains("not supported yet"))
    }

    @Test
    fun `remote package source is rejected until remote loading is implemented`() {
        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.RemotePackageUrl(url = "https://example.com/plugin.wlm"),
            ),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertEquals(WasmlineLoadState.CODE_FAILURE, failure.code)
        assertTrue(failure.cause.contains("Remote package source 'https://example.com/plugin.wlm'"))
        assertTrue(failure.cause.contains("not supported yet"))
    }

    @Test
    fun `top level artifact path entrypoint delegates to runtime-style local loading`() {
        val result = loadWasmline(artifactPath = "/tmp/missing-plugin.pwasm")

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertEquals(WasmlineLoadState.CODE_FAILURE, failure.code)
        assertTrue(failure.cause.contains("artifact file not found"))
        assertTrue(failure.cause.contains("/tmp/missing-plugin.pwasm"))
    }
}

