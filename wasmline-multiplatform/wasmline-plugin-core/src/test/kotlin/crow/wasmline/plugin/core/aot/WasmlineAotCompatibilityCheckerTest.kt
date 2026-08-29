package crow.wasmline.plugin.core.aot

import crow.wasmline.WasmlineEngineKind
import java.io.File
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies bounded remote checks, cache behavior, and stable compatibility reports.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
class WasmlineAotCompatibilityCheckerTest {
    @Test
    fun reportsGenerationGapAndAffectedBackend() = withTemporaryDirectory { directory ->
        val remote = catalogWithSecondGeneration(changedBackends = listOf(WasmlineEngineKind.CRANELIFT))
        val transport = ScriptedTransport.forCatalog(remote)
        val messages = mutableListOf<String>()
        val result = checker(transport).check(request(directory, messages::add))

        assertEquals(AotCompatibilityRemoteStatus.AVAILABLE, result.report.remoteCheckStatus)
        assertEquals(1, result.report.aotGenerationGap)
        assertEquals(1, result.report.newerGenerationCount)
        assertEquals(listOf(WasmlineEngineKind.CRANELIFT), result.report.affectedBackends)
        assertTrue(result.report.affectedWasmlineRanges.isNotEmpty())
        assertEquals("WLAOT001", result.warningCode)
        assertTrue(messages.single().contains("AOT generation gap: 1"))
        assertTrue(messages.single().contains("Affected backends: CRANELIFT"))
    }

    @Test
    fun backendFilteringDoesNotReportAnUnaffectedPulleyArtifact() = withTemporaryDirectory { directory ->
        val remote = catalogWithSecondGeneration(changedBackends = listOf(WasmlineEngineKind.CRANELIFT))
        val result = checker(ScriptedTransport.forCatalog(remote)).check(
            request(directory, requestedBackends = setOf(WasmlineEngineKind.PULLEY)),
        )

        assertEquals(1, result.report.aotGenerationGap)
        assertEquals(0, result.report.newerGenerationCount)
        assertTrue(result.report.affectedBackends.isEmpty())
        assertTrue(result.report.affectedWasmlineRanges.isEmpty())
    }

    @Test
    fun reportsZeroGapWhenOnlyWasmlineVersionChanges() = withTemporaryDirectory { directory ->
        val remote = localCatalog().copy(currentWasmlineVersion = "2.0.0")
        val messages = mutableListOf<String>()
        val result = checker(ScriptedTransport.forCatalog(remote)).check(request(directory, messages::add))

        assertEquals(0, result.report.aotGenerationGap)
        assertEquals(0, result.report.newerGenerationCount)
        assertTrue(messages.single().contains("AOT generation gap: 0"))
    }

    @Test
    fun reportsLocallyKnownGenerationOmittedBySelection() = withTemporaryDirectory { directory ->
        val local = catalogWithSecondGeneration(changedBackends = WasmlineEngineKind.entries)
        val result = checker(ScriptedTransport.forCatalog(local)).check(
            request(
                directory = directory,
                localCatalog = local,
                selector = "versionRanges",
                selectedGenerations = listOf(1),
            ),
        )

        assertEquals(listOf(2), result.report.omittedKnownGenerations)
        assertTrue(result.report.affectedWasmlineRanges.single().contains("AOT generation 2"))
        assertTrue(result.warningMessage.contains("Native hosts using omitted AOT profiles cannot load"))
    }

    @Test
    fun customRangeMayIntentionallyOmitCurrentGeneration() = withTemporaryDirectory { directory ->
        val local = catalogWithSecondGeneration(changedBackends = listOf(WasmlineEngineKind.PULLEY))
        val result = checker(ScriptedTransport.forCatalog(local)).check(
            request(
                directory = directory,
                localCatalog = local,
                selector = "versionRanges",
                selectedGenerations = listOf(1),
                requestedBackends = setOf(WasmlineEngineKind.PULLEY),
            ),
        )

        assertEquals(listOf(2), result.report.omittedKnownGenerations)
        assertEquals(listOf(WasmlineEngineKind.PULLEY), result.report.affectedBackends)
        assertTrue(result.warningMessage.contains("Selector: versionRanges"))
    }

    @Test
    fun laterGenerationReusingBackendProfileCoversAnEarlierOmittedGeneration() = withTemporaryDirectory { directory ->
        val local = catalogWithReusedCraneliftProfile()
        val result = checker(ScriptedTransport.forCatalog(local)).check(
            request(
                directory = directory,
                localCatalog = local,
                selector = "versionRanges",
                selectedGenerations = listOf(1, 3),
                requestedBackends = setOf(WasmlineEngineKind.CRANELIFT),
            ),
        )

        assertTrue(result.report.omittedKnownGenerations.isEmpty())
        assertTrue(result.report.affectedWasmlineRanges.isEmpty())
        assertTrue(result.report.affectedBackends.isEmpty())
    }

    @Test
    fun checksumFailureIsAdvisoryAndDoesNotFailTheCheck() = withTemporaryDirectory { directory ->
        val transport = ScriptedTransport.forCatalog(localCatalog(), validChecksum = false)
        val messages = mutableListOf<String>()
        val result = checker(transport).check(request(directory, messages::add))

        assertUnavailable(result, "checksum verification failed")
        assertTrue(messages.single().contains("could not be checked"))
        assertTrue(reportFile(directory).isFile)
    }

    @Test
    fun httpErrorIsAdvisoryAndDoesNotExposeResponseBody() = withTemporaryDirectory { directory ->
        val secret = "authorization=Bearer-sensitive-value"
        val transport = ScriptedTransport { url, _, _ ->
            if (url.endsWith(".sha256")) error("Checksum should not be requested.")
            AotCompatibilityHttpResponse(503, body = secret.encodeToByteArray())
        }
        val result = checker(transport).check(request(directory))

        assertUnavailable(result, "remote server returned an error")
        assertFalse(result.warningMessage.contains(secret))
        assertFalse(reportFile(directory).readText().contains(secret))
    }

    @Test
    fun oversizedCatalogAndChecksumResponsesAreAdvisory() = withTemporaryDirectory { directory ->
        val oversized = ByteArray(256 * 1024 + 1)
        val catalogResult = checker(
            ScriptedTransport { _, _, _ -> AotCompatibilityHttpResponse(200, body = oversized) },
        ).check(request(directory))
        assertUnavailable(catalogResult, "remote catalog validation failed")

        val body = encoded(localCatalog())
        val checksumResult = checker(
            ScriptedTransport { url, _, _ ->
                if (url.endsWith(".sha256")) {
                    AotCompatibilityHttpResponse(200, body = oversized)
                } else {
                    AotCompatibilityHttpResponse(200, body = body)
                }
            },
        ).check(request(directory, cacheDirectoryName = "checksum-cache"))
        assertUnavailable(checksumResult, "checksum verification failed")
    }

    @Test
    fun invalidJsonAndSchemaAreAdvisory() = withTemporaryDirectory { directory ->
        listOf(
            "not-json".encodeToByteArray(),
            """{"schemaVersion":99}""".encodeToByteArray(),
        ).forEachIndexed { index, body ->
            val result = checker(transportForBody(body)).check(
                request(directory, cacheDirectoryName = "invalid-$index"),
            )

            assertUnavailable(result, "remote catalog schema is invalid")
        }
    }

    @Test
    fun timeoutIsAdvisoryAndUsesStableFailureText() = withTemporaryDirectory { directory ->
        val sensitiveMessage = "request for https://token@example.invalid failed"
        val result = checker(
            ScriptedTransport { _, _, _ -> throw SocketTimeoutException(sensitiveMessage) },
        ).check(request(directory))

        assertUnavailable(result, "request timed out after 5 seconds")
        assertFalse(result.warningMessage.contains(sensitiveMessage))
    }

    @Test
    fun offlineModeDoesNotUseTransport() = withTemporaryDirectory { directory ->
        val transport = ScriptedTransport.forCatalog(localCatalog())
        val result = checker(transport).check(request(directory).copy(offline = true))

        assertEquals(0, transport.calls.size)
        assertEquals(AotCompatibilityRemoteStatus.OFFLINE, result.report.remoteCheckStatus)
        assertEquals(AotCompatibilityCatalogSource.LOCAL_ONLY, result.report.catalogSource)
        assertEquals("WLAOT002", result.warningCode)
    }

    @Test
    fun freshCacheAvoidsNetworkAndExpiredCacheUsesConditionalRequest() = withTemporaryDirectory { directory ->
        val remote = localCatalog()
        val now = Instant.parse("2026-08-29T00:00:00Z")
        val firstTransport = ScriptedTransport.forCatalog(remote)
        checker(firstTransport, now).check(request(directory))
        assertEquals(2, firstTransport.calls.size)

        val freshTransport = ScriptedTransport { _, _, _ -> error("Fresh cache must avoid network access.") }
        val fresh = checker(freshTransport, now.plusSeconds(23 * 60 * 60)).check(request(directory))
        assertEquals(0, freshTransport.calls.size)
        assertEquals(AotCompatibilityCatalogSource.CACHE, fresh.report.catalogSource)

        val notModifiedTransport = ScriptedTransport { _, headers, _ ->
            assertEquals("catalog-v1", headers["If-None-Match"])
            AotCompatibilityHttpResponse(304)
        }
        val refreshed = checker(notModifiedTransport, now.plusSeconds(24 * 60 * 60 + 1)).check(request(directory))
        assertEquals(1, notModifiedTransport.calls.size)
        assertEquals(AotCompatibilityCatalogSource.CACHE, refreshed.report.catalogSource)
        assertEquals(AotCompatibilityRemoteStatus.AVAILABLE, refreshed.report.remoteCheckStatus)
    }

    @Test
    fun notModifiedWithoutCacheIsAdvisory() = withTemporaryDirectory { directory ->
        val result = checker(
            ScriptedTransport { _, _, _ -> AotCompatibilityHttpResponse(304) },
        ).check(request(directory))

        assertUnavailable(result, "remote catalog validation failed")
    }

    @Test
    fun redirectPolicyRejectsInsecureAndSecondRedirects() {
        val origin = URI("https://example.invalid/aot-compatibility.json")
        val first = resolveAotCompatibilityRedirect(origin, "/next", 0)

        assertEquals(URI("https://example.invalid/next"), first.first)
        assertEquals(1, first.second)
        assertFailsWith<IllegalArgumentException> {
            resolveAotCompatibilityRedirect(first.first, "/third", first.second)
        }
        assertFailsWith<IllegalArgumentException> {
            resolveAotCompatibilityRedirect(origin, "http://example.invalid/unsafe", 0)
        }
        assertFailsWith<IllegalArgumentException> {
            resolveAotCompatibilityRedirect(origin, null, 0)
        }
    }

    @Test
    fun suppressionKeepsTheReportButOmitsLoggerOutput() = withTemporaryDirectory { directory ->
        val messages = mutableListOf<String>()
        val result = checker(ScriptedTransport.forCatalog(localCatalog()))
            .check(request(directory, messages::add).copy(warningSuppressed = true))

        assertTrue(messages.isEmpty())
        assertTrue(result.report.warningSuppressed)
        assertTrue(reportFile(directory).isFile)
    }

    private fun checker(
        transport: AotCompatibilityHttpTransport = JvmAotCompatibilityHttpTransport(),
        now: Instant = Instant.parse("2026-08-29T00:00:00Z"),
    ) = WasmlineAotCompatibilityChecker(transport = transport, clock = { now })

    private fun request(
        directory: File,
        logger: (String) -> Unit = {},
        localCatalog: WasmlineAotReleaseCatalog = localCatalog(),
        selector: String = "current",
        selectedGenerations: List<Int> = listOf(localCatalog.ranges.last().aotGeneration),
        requestedBackends: Set<WasmlineEngineKind> = setOf(WasmlineEngineKind.CRANELIFT, WasmlineEngineKind.PULLEY),
        cacheDirectoryName: String = "cache",
        remoteUrl: String = "https://example.invalid/aot-compatibility.json",
    ): WasmlineAotCompatibilityCheckRequest = WasmlineAotCompatibilityCheckRequest(
        localCatalog = localCatalog,
        localWasmlineVersion = localCatalog.currentWasmlineVersion,
        selector = selector,
        selectedAotGenerations = selectedGenerations,
        requestedBackends = requestedBackends,
        reportFile = reportFile(directory),
        cacheDirectory = File(directory, cacheDirectoryName),
        remoteUrl = remoteUrl,
        logger = logger,
    )

    private fun catalogWithSecondGeneration(changedBackends: List<WasmlineEngineKind>): WasmlineAotReleaseCatalog {
        val local = localCatalog()
        return local.copy(
            currentWasmlineVersion = "2.0.0",
            ranges = local.ranges + WasmlineAotReleaseRange(
                fromWasmlineVersion = "2.0.0",
                aotGeneration = 2,
                wasmtimeDistributionVersion = "49.0.0.1",
                changedBackends = changedBackends,
            ),
        )
    }

    private fun catalogWithReusedCraneliftProfile(): WasmlineAotReleaseCatalog {
        val local = localCatalog()
        return local.copy(
            currentWasmlineVersion = "3.0.0",
            ranges = local.ranges +
                WasmlineAotReleaseRange(
                    fromWasmlineVersion = "2.0.0",
                    aotGeneration = 2,
                    wasmtimeDistributionVersion = "49.0.0.1",
                    changedBackends = listOf(WasmlineEngineKind.CRANELIFT),
                ) +
                WasmlineAotReleaseRange(
                    fromWasmlineVersion = "3.0.0",
                    aotGeneration = 3,
                    wasmtimeDistributionVersion = "49.0.0.1",
                    changedBackends = listOf(WasmlineEngineKind.PULLEY),
                ),
        )
    }

    private fun transportForBody(body: ByteArray): ScriptedTransport = ScriptedTransport { url, _, _ ->
        if (url.endsWith(".sha256")) {
            AotCompatibilityHttpResponse(200, body = "${sha256(body)}  aot-compatibility.json\n".encodeToByteArray())
        } else {
            AotCompatibilityHttpResponse(200, body = body)
        }
    }

    private fun assertUnavailable(result: WasmlineAotCompatibilityCheckResult, expectedReason: String) {
        assertEquals(AotCompatibilityRemoteStatus.UNAVAILABLE, result.report.remoteCheckStatus)
        assertEquals(AotCompatibilityCatalogSource.LOCAL_ONLY, result.report.catalogSource)
        assertEquals("WLAOT002", result.warningCode)
        assertEquals(expectedReason, result.report.remoteFailureReason)
    }

    /**
     * Provides deterministic responses for checker tests without network access.
     *
     * Date: 2026-08-29
     * Author: crowforkotlin
     */
    private class ScriptedTransport(private val response: (String, Map<String, String>, Duration) -> AotCompatibilityHttpResponse) :
        AotCompatibilityHttpTransport {
        val calls: MutableList<String> = mutableListOf()

        override fun fetch(url: String, headers: Map<String, String>, timeout: Duration): AotCompatibilityHttpResponse {
            calls += url
            return response(url, headers, timeout)
        }

        /** Creates a transport that serves one valid catalog and checksum pair. */
        companion object {
            fun forCatalog(catalog: WasmlineAotReleaseCatalog, validChecksum: Boolean = true): ScriptedTransport {
                val body = encoded(catalog)
                return ScriptedTransport { url, _, _ ->
                    if (url.endsWith(".sha256")) {
                        val checksum = if (validChecksum) sha256(body) else "0".repeat(64)
                        AotCompatibilityHttpResponse(
                            200,
                            body = "$checksum  aot-compatibility.json\n".encodeToByteArray(),
                        )
                    } else {
                        AotCompatibilityHttpResponse(200, headers = mapOf("ETag" to "catalog-v1"), body = body)
                    }
                }
            }
        }
    }

    private fun reportFile(directory: File): File = File(directory, "build/reports/wasmline/aot-compatibility-check.json")
}

private inline fun withTemporaryDirectory(block: (File) -> Unit) {
    val directory = createTempDirectory("wasmline-aot-checker-test").toFile()
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}

private fun localCatalog(): WasmlineAotReleaseCatalog = AotCompatibilityCatalog.publicReleaseCatalog()

private fun encoded(catalog: WasmlineAotReleaseCatalog): ByteArray =
    WasmlineAotReleaseCatalogCodec.encodePublic(catalog).encodeToByteArray()

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }
