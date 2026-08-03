package crow.wasmline.loader

import crow.wasmline.WasmlineLoadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Verifies top-level loader routing and resolver delegation failures. */
class DefaultWasmlineLoaderTest {

    @Test
    fun `top level request entrypoint rejects local package source until package resolution is implemented`() {
        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.LocalManifestPath(path = "/tmp/plugin.wlm"),
            ),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertEquals(WasmlineLoadState.CODE_FAILURE, failure.code)
        assertTrue(failure.cause.contains("Local package source '/tmp/plugin.wlm'"))
        assertTrue(failure.cause.contains("request.resolvers.localPackage"))
    }

    @Test
    fun `local package source is rejected until package resolution is implemented`() {
        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.LocalManifestPath(path = "/tmp/plugin.wlm"),
            ),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertEquals(WasmlineLoadState.CODE_FAILURE, failure.code)
        assertTrue(failure.cause.contains("Local package source '/tmp/plugin.wlm'"))
        assertTrue(failure.cause.contains("request.resolvers.localPackage"))
    }

    @Test
    fun `remote package source without networkClient or resolver returns failure`() {
        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.RemoteManifestUrl(url = "https://example.com/plugin.wlm"),
            ),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertEquals(WasmlineLoadState.CODE_FAILURE, failure.code)
        assertTrue(failure.cause.contains("Remote package source 'https://example.com/plugin.wlm'"))
        assertTrue(failure.cause.contains("request.resolvers.remotePackage or request.config.networkClient"))
    }

    @Test
    fun `local package source can delegate to a configured resolver`() {
        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.LocalManifestPath(path = "/tmp/plugin.wlm"),
                metadata = mapOf("channel" to "debug"),
                resolvers = WasmlineSourceResolvers(
                    localPackage = WasmlineLocalPackageResolver { source, request ->
                        assertEquals("/tmp/plugin.wlm", source.path)
                        assertEquals("debug", request.metadata["channel"])
                        WasmlineSourceResolution.ContinueWith(
                            WasmlineSource.LocalArtifactPath(path = "/tmp/resolved-plugin.pwasm"),
                        )
                    },
                ),
            ),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertTrue(failure.cause.contains("artifact file not found"))
        assertTrue(failure.cause.contains("/tmp/resolved-plugin.pwasm"))
    }

    @Test
    fun `remote package source can chain through custom resolvers`() {
        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.RemoteManifestUrl(url = "https://example.com/plugin.wlm"),
                resolvers = WasmlineSourceResolvers(
                    remotePackage = WasmlineRemotePackageResolver { source, _ ->
                        assertEquals("https://example.com/plugin.wlm", source.url)
                        WasmlineSourceResolution.ContinueWith(
                            WasmlineSource.LocalManifestPath(path = "/tmp/downloaded-plugin.wlm"),
                        )
                    },
                    localPackage = WasmlineLocalPackageResolver { source, _ ->
                        assertEquals("/tmp/downloaded-plugin.wlm", source.path)
                        WasmlineSourceResolution.ContinueWith(
                            WasmlineSource.LocalArtifactPath(path = "/tmp/resolved-plugin.cwasm"),
                        )
                    },
                ),
            ),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertTrue(failure.cause.contains("/tmp/resolved-plugin.cwasm"))
    }

    @Test
    fun `resolver can complete with a terminal load state`() {
        val terminal = WasmlineLoadState.Failure(
            code = WasmlineLoadState.CODE_FAILURE,
            cause = "Manifest signature mismatch",
        )
        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.LocalManifestPath(path = "/tmp/plugin.wlm"),
                resolvers = WasmlineSourceResolvers(
                    localPackage = WasmlineLocalPackageResolver { _, _ ->
                        WasmlineSourceResolution.Complete(terminal)
                    },
                ),
            ),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertEquals("Manifest signature mismatch", failure.cause)
    }

    @Test
    fun `top level artifact path entrypoint delegates to runtime-style local loading`() {
        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(source = WasmlineSource.LocalArtifactPath("/tmp/missing-plugin.pwasm")),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertEquals(WasmlineLoadState.CODE_FAILURE, failure.code)
        assertTrue(failure.cause.contains("artifact file not found"))
        assertTrue(failure.cause.contains("/tmp/missing-plugin.pwasm"))
    }
}
