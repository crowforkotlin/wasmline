package crow.wasmline.loader

import crow.wasmline.WasmlineLoadState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Verifies top-level loader routing and resolver delegation failures. */
class DefaultWasmlineLoaderTest {

    @Test
    fun `top level request entrypoint uses built-in local package resolution`() = runTest {
        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.LocalManifestPath(path = "/tmp/plugin.wlm"),
            ),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertEquals(WasmlineLoadState.CODE_FAILURE, failure.code)
        assertTrue(failure.cause.contains("Local package manifest not found"))
    }

    @Test
    fun `local package source reports a missing manifest before signature verification`() = runTest {
        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.LocalManifestPath(path = "/tmp/plugin.wlm"),
            ),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertEquals(WasmlineLoadState.CODE_FAILURE, failure.code)
        assertTrue(failure.cause.contains("Local package manifest not found"))
    }

    @Test
    fun `remote package source without networkClient or resolver returns failure`() = runTest {
        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.RemoteManifestUrl(url = "https://example.com/plugin.wlm"),
            ),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertEquals(WasmlineLoadState.CODE_FAILURE, failure.code)
        assertTrue(failure.cause.contains("https://example.com/plugin.wlm"))
        assertTrue(failure.cause.contains("request.options.networkClient or request.resolvers.remotePackage"))
    }

    @Test
    fun `public request overload exposes custom resolvers`() = runTest {
        val result = WasmlineLoader.load(
            WasmlineLoadRequest(
                source = WasmlineSource.RemoteManifestUrl("https://example.com/custom.wlm"),
                resolvers = WasmlineSourceResolvers(
                    remotePackage = WasmlineRemotePackageResolver { _, _ ->
                        WasmlineSourceResolution.Complete(
                            WasmlineLoadState.Failure(
                                code = WasmlineLoadState.CODE_FAILURE,
                                cause = "public request resolver",
                            ),
                        )
                    },
                ),
            ),
        )

        val failure = assertIs<crow.wasmline.WasmlineLoadResult.Failure>(result)
        assertEquals("public request resolver", failure.cause)
    }

    @Test
    fun `custom package resolver direct artifact is caller trusted`() = runTest {
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
        assertTrue(failure.cause.contains("explicit artifactFormat"))
        assertTrue(!failure.cause.contains("requires trustedKeys"))
    }

    @Test
    fun `remote package source can chain through custom resolvers`() = runTest {
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
        assertTrue(failure.cause.contains("explicit artifactFormat"))
    }

    @Test
    fun `resolver can complete with a terminal load state`() = runTest {
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
    fun `direct artifact path is caller trusted and still requires explicit native format`() = runTest {
        val result = DefaultWasmlineLoader.load(
            WasmlineLoadRequest(source = WasmlineSource.LocalArtifactPath("/tmp/missing-plugin.pwasm")),
        )

        val failure = assertIs<WasmlineLoadState.Failure>(result)
        assertEquals(WasmlineLoadState.CODE_FAILURE, failure.code)
        assertTrue(failure.cause.contains("explicit artifactFormat"))
        assertTrue(!failure.cause.contains("requires trustedKeys"))
    }
}
