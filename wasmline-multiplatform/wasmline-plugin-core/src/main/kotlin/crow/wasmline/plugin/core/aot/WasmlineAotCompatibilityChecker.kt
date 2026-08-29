package crow.wasmline.plugin.core.aot

import crow.wasmline.WasmlineEngineKind
import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.Properties

/**
 * Identifies how the checker obtained the latest published compatibility catalog.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
@Serializable
@InternalWasmlineToolingApi
enum class AotCompatibilityCatalogSource {
    NETWORK,
    CACHE,
    LOCAL_ONLY,
}

/**
 * Identifies the bounded result of the optional remote check.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
@Serializable
@InternalWasmlineToolingApi
enum class AotCompatibilityRemoteStatus {
    AVAILABLE,
    UNAVAILABLE,
    OFFLINE,
}

/**
 * Defines the input to one local and online AOT compatibility check.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
data class WasmlineAotCompatibilityCheckRequest(
    val localCatalog: WasmlineAotReleaseCatalog,
    val localWasmlineVersion: String,
    val selector: String,
    val selectedAotGenerations: Collection<Int>,
    val selectedProfileIds: Collection<String> = emptyList(),
    val nativeTargetCount: Int = 0,
    val requestedBackends: Set<WasmlineEngineKind>,
    val reportFile: File,
    val cacheDirectory: File = defaultAotCompatibilityCacheDirectory(),
    val remoteUrl: String = DEFAULT_REMOTE_CATALOG_URL,
    val offline: Boolean = false,
    val warningSuppressed: Boolean = false,
    val logger: (String) -> Unit = {},
)

/**
 * Contains a stable machine-readable compatibility check report.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
@Serializable
@InternalWasmlineToolingApi
data class WasmlineAotCompatibilityCheckReport(
    val schemaVersion: Int = 1,
    val checkedAt: String,
    val catalogSource: AotCompatibilityCatalogSource,
    val localWasmlineVersion: String,
    val latestPublishedWasmlineVersion: String? = null,
    val selector: String,
    val selectedAotGenerations: List<Int>,
    val selectedProfileIds: List<String> = emptyList(),
    val nativeTargetCount: Int = 0,
    val localCurrentAotGeneration: Int,
    val latestPublishedAotGeneration: Int? = null,
    val aotGenerationGap: Int,
    val newerGenerationCount: Int,
    val omittedKnownGenerations: List<Int>,
    val affectedWasmlineRanges: List<String>,
    val affectedBackends: List<WasmlineEngineKind>,
    val warningSuppressed: Boolean,
    val remoteCheckStatus: AotCompatibilityRemoteStatus,
    val remoteFailureReason: String? = null,
    val requestedBackends: List<WasmlineEngineKind>,
    val documentationUrl: String = DEFAULT_DOCUMENTATION_URL,
)

/**
 * Returns the result and report produced by one compatibility check.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
data class WasmlineAotCompatibilityCheckResult(
    val report: WasmlineAotCompatibilityCheckReport,
    val warningCode: String,
    val warningMessage: String,
)

/**
 * Performs a local catalog validation and a bounded advisory check against the latest release.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
class WasmlineAotCompatibilityChecker(
    private val transport: AotCompatibilityHttpTransport = JvmAotCompatibilityHttpTransport(),
    private val clock: () -> Instant = Instant::now,
) {
    /** Executes the check, writes an atomic report, and emits one optional warning. */
    fun check(request: WasmlineAotCompatibilityCheckRequest): WasmlineAotCompatibilityCheckResult {
        validateRequest(request)
        request.localCatalog.validate(requireProfileBindings = false)
        val selected = request.selectedAotGenerations.toSet().sorted()
        val selectedProfileIds = request.selectedProfileIds.distinct().sorted()
        val localRanges = request.localCatalog.ranges
        val localCurrentGeneration = localRanges.last().aotGeneration
        val localGenerations = localRanges.map { it.aotGeneration }.toSet()
        require(selected.all { it in localGenerations }) {
            "AOT checker selection contains a generation outside the local catalog."
        }
        val localEpochs = profileEpochs(request.localCatalog)
        val selectedEpochs = selectedEpochsByBackend(selected, localEpochs, request.requestedBackends)
        val omittedLocal = localRanges
            .map { it.aotGeneration }
            .filter { it !in selected }
            .filter { generation ->
                val range = localRanges.first { it.aotGeneration == generation }
                range.changedBackends.any { it in request.requestedBackends } &&
                    affectedBackendsForRange(range, localEpochs, selectedEpochs, request.requestedBackends).isNotEmpty()
            }
        val checkedAt = clock()
        val remote = resolveRemoteCatalog(request, checkedAt.plus(REQUEST_TIMEOUT))
        val latest = remote.catalog
        val latestEpochs = latest?.let(::profileEpochs)
        val affectedCatalog = latest ?: request.localCatalog
        val affectedEpochs = latestEpochs ?: localEpochs
        val affectedSelectedEpochs = if (latest == null) {
            selectedEpochs
        } else {
            selectedEpochsByBackend(selected, affectedEpochs, request.requestedBackends)
        }
        val aotGenerationGap = latest?.ranges
            ?.lastOrNull()
            ?.let { maxOf(0, it.aotGeneration - localCurrentGeneration) }
            ?: 0
        val newerGenerationCount = latest?.ranges
            ?.count { range ->
                range.aotGeneration > localCurrentGeneration &&
                    latestEpochs != null &&
                    affectedBackendsForRange(range, latestEpochs, affectedSelectedEpochs, request.requestedBackends)
                        .isNotEmpty() &&
                    range.changedBackends.any { it in request.requestedBackends }
            }
            ?: 0
        val affectedRanges = buildAffectedRanges(
            catalog = affectedCatalog,
            selectedGenerations = selected,
            localCurrentGeneration = localCurrentGeneration,
            requestedBackends = request.requestedBackends,
            epochs = affectedEpochs,
            selectedEpochs = affectedSelectedEpochs,
        )
        val affectedBackends = affectedCatalog.ranges
            .flatMap { range -> affectedBackendsForRange(range, affectedEpochs, affectedSelectedEpochs, request.requestedBackends) }
            .distinct()
            .sortedBy(WasmlineEngineKind::name)
        val report = WasmlineAotCompatibilityCheckReport(
            schemaVersion = REPORT_SCHEMA_VERSION,
            checkedAt = checkedAt.toString(),
            catalogSource = remote.source,
            localWasmlineVersion = request.localWasmlineVersion,
            latestPublishedWasmlineVersion = latest?.currentWasmlineVersion,
            selector = request.selector,
            selectedAotGenerations = selected,
            selectedProfileIds = selectedProfileIds,
            nativeTargetCount = request.nativeTargetCount,
            localCurrentAotGeneration = localCurrentGeneration,
            latestPublishedAotGeneration = latest?.ranges?.last()?.aotGeneration,
            aotGenerationGap = aotGenerationGap,
            newerGenerationCount = newerGenerationCount,
            omittedKnownGenerations = omittedLocal,
            affectedWasmlineRanges = affectedRanges,
            affectedBackends = affectedBackends,
            warningSuppressed = request.warningSuppressed,
            remoteCheckStatus = remote.status,
            remoteFailureReason = remote.failureReason,
            requestedBackends = request.requestedBackends.sortedBy(WasmlineEngineKind::name),
        )
        writeReport(request.reportFile, report)
        val warningCode = if (remote.status == AotCompatibilityRemoteStatus.AVAILABLE) {
            WARNING_REVIEW
        } else {
            WARNING_REMOTE_UNAVAILABLE
        }
        val warningMessage = formatWarning(warningCode, report, request.reportFile)
        if (!request.warningSuppressed) request.logger(warningMessage)
        return WasmlineAotCompatibilityCheckResult(report, warningCode, warningMessage)
    }

    private fun validateRequest(request: WasmlineAotCompatibilityCheckRequest) {
        require(isStableWasmlineVersion(request.localWasmlineVersion)) {
            "AOT checker local Wasmline version must use x.y.z."
        }
        require(compareWasmlineVersions(request.localWasmlineVersion, request.localCatalog.currentWasmlineVersion) == 0) {
            "AOT checker local Wasmline version does not match the local catalog."
        }
        require(request.requestedBackends.isNotEmpty()) { "AOT checker requires at least one backend." }
        require(request.selectedAotGenerations.all { it > 0 }) {
            "AOT checker generation numbers must be positive."
        }
        require(request.selectedAotGenerations.any()) {
            "AOT checker requires at least one selected generation."
        }
        require(request.selectedProfileIds.all { PROFILE_ID_PATTERN.matches(it) }) {
            "AOT checker profile IDs must use sha256:<digest>."
        }
        require(request.nativeTargetCount >= 0) {
            "AOT checker native target count must not be negative."
        }
        require(request.selector in AOT_COMPATIBILITY_SELECTOR_NAMES) {
            "AOT checker selector is invalid: '${request.selector}'."
        }
        val uri = runCatching { URI(request.remoteUrl) }.getOrElse {
            throw IllegalArgumentException("AOT checker remote URL is invalid.", it)
        }
        validateHttpsUri(uri, "AOT checker remote URL")
    }

    private fun resolveRemoteCatalog(request: WasmlineAotCompatibilityCheckRequest, deadline: Instant): RemoteCatalogResult {
        if (request.offline) {
            return RemoteCatalogResult(
                catalog = null,
                source = AotCompatibilityCatalogSource.LOCAL_ONLY,
                status = AotCompatibilityRemoteStatus.OFFLINE,
                failureReason = "offline mode",
            )
        }
        val cache = CatalogCache(request.cacheDirectory, request.remoteUrl)
        val cached = usableCachedCatalog(request.localCatalog, cache.read())
        if (cached != null && !cached.isExpired(clock())) {
            return RemoteCatalogResult(
                catalog = cached.catalog,
                source = AotCompatibilityCatalogSource.CACHE,
                status = AotCompatibilityRemoteStatus.AVAILABLE,
            )
        }
        return runCatching {
            cache.withLock(deadline, clock) {
                val refreshed = usableCachedCatalog(request.localCatalog, cache.read())
                if (refreshed != null && !refreshed.isExpired(clock())) {
                    return@withLock RemoteCatalogResult(
                        catalog = refreshed.catalog,
                        source = AotCompatibilityCatalogSource.CACHE,
                        status = AotCompatibilityRemoteStatus.AVAILABLE,
                    )
                }
                val metadata = refreshed?.metadata ?: CacheMetadata()
                val response = transport.fetch(
                    url = request.remoteUrl,
                    headers = buildMap {
                        metadata.etag?.let { put("If-None-Match", it) }
                        metadata.lastModified?.let { put("If-Modified-Since", it) }
                    },
                    timeout = remainingTimeout(deadline),
                )
                when (response.statusCode) {
                    HTTP_NOT_MODIFIED -> {
                        require(refreshed != null) { "HTTP 304 was returned without a valid cached catalog." }
                        cache.touch(refreshed.metadata, clock().toEpochMilli())
                        RemoteCatalogResult(
                            catalog = refreshed.catalog,
                            source = AotCompatibilityCatalogSource.CACHE,
                            status = AotCompatibilityRemoteStatus.AVAILABLE,
                        )
                    }

                    in 200..299 -> {
                        val body = response.body
                        require(body.size <= MAX_RESPONSE_BYTES) { "remote catalog exceeds the response size limit" }
                        val checksum = fetchChecksum(request.remoteUrl, deadline)
                        require(sha256Hex(body) == checksum) { "remote catalog checksum mismatch" }
                        val catalog = WasmlineAotReleaseCatalogCodec.decodePublic(body.decodeToString())
                        validateRemoteHistory(request.localCatalog, catalog)
                        cache.write(
                            body = body,
                            metadata = CacheMetadata(
                                fetchedAtMillis = clock().toEpochMilli(),
                                etag = response.header("ETag"),
                                lastModified = response.header("Last-Modified"),
                                remoteUrl = request.remoteUrl,
                            ),
                        )
                        RemoteCatalogResult(
                            catalog = catalog,
                            source = AotCompatibilityCatalogSource.NETWORK,
                            status = AotCompatibilityRemoteStatus.AVAILABLE,
                        )
                    }

                    else -> error("HTTP ${response.statusCode}")
                }
            }
        }.getOrElse { error ->
            RemoteCatalogResult(
                catalog = null,
                source = AotCompatibilityCatalogSource.LOCAL_ONLY,
                status = AotCompatibilityRemoteStatus.UNAVAILABLE,
                failureReason = classifyFailure(error),
            )
        }
    }

    private fun fetchChecksum(remoteUrl: String, deadline: Instant): String {
        val checksumUrl = checksumUrl(remoteUrl)
        val checksumResponse = transport.fetch(checksumUrl, emptyMap(), remainingTimeout(deadline))
        require(checksumResponse.statusCode in 200..299) {
            "remote catalog checksum request returned HTTP ${checksumResponse.statusCode}"
        }
        require(checksumResponse.body.size <= MAX_RESPONSE_BYTES) {
            "remote catalog checksum exceeds the response size limit"
        }
        val token = checksumResponse.body.decodeToString()
            .trim()
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.lowercase()
        require(token != null && CHECKSUM_PATTERN.matches(token)) {
            "remote catalog checksum is missing or invalid"
        }
        return token
    }

    private fun validateRemoteHistory(localCatalog: WasmlineAotReleaseCatalog, remoteCatalog: WasmlineAotReleaseCatalog) {
        require(remoteCatalog.ranges.size >= localCatalog.ranges.size) {
            "remote catalog omits previously published AOT generations"
        }
        localCatalog.ranges.indices.forEach { index ->
            require(remoteCatalog.ranges[index] == localCatalog.ranges[index]) {
                "remote catalog changes a previously published AOT generation"
            }
        }
        require(compareWasmlineVersions(remoteCatalog.currentWasmlineVersion, localCatalog.currentWasmlineVersion) >= 0) {
            "remote catalog current Wasmline version predates the local release"
        }
        require(
            compareWasmlineVersions(
                remoteCatalog.minimumSupportedWasmlineVersion,
                localCatalog.minimumSupportedWasmlineVersion,
            ) >= 0,
        ) {
            "remote catalog minimum supported Wasmline version moves backward"
        }
    }

    private fun usableCachedCatalog(localCatalog: WasmlineAotReleaseCatalog, cached: CachedCatalog?): CachedCatalog? =
        cached?.takeIf { candidate ->
            runCatching { validateRemoteHistory(localCatalog, candidate.catalog) }.isSuccess
        }

    private fun checksumUrl(remoteUrl: String): String {
        val uri = URI(remoteUrl)
        return URI(
            uri.scheme,
            uri.userInfo,
            uri.host,
            uri.port,
            (uri.path ?: "") + ".sha256",
            uri.query,
            null,
        ).toString()
    }

    private fun remainingTimeout(deadline: Instant): Duration {
        val remainingMillis = Duration.between(clock(), deadline).toMillis()
        if (remainingMillis <= 0) throw SocketTimeoutException(AOT_COMPATIBILITY_TIMEOUT_MESSAGE)
        return Duration.ofMillis(remainingMillis)
    }

    /** Maps each backend and generation to its immutable profile epoch. */
    private fun profileEpochs(catalog: WasmlineAotReleaseCatalog): Map<WasmlineEngineKind, Map<Int, Int>> {
        val currentEpochs = WasmlineEngineKind.entries.associateWith { -1 }.toMutableMap()
        val epochs = WasmlineEngineKind.entries.associateWith { linkedMapOf<Int, Int>() }
        catalog.ranges.forEach { range ->
            WasmlineEngineKind.entries.forEach { backend ->
                if (backend in range.changedBackends) {
                    currentEpochs[backend] = currentEpochs.getValue(backend) + 1
                }
                epochs.getValue(backend)[range.aotGeneration] = currentEpochs.getValue(backend)
            }
        }
        return epochs
    }

    /** Resolves the profile epochs represented by the selected generations. */
    private fun selectedEpochsByBackend(
        selectedGenerations: List<Int>,
        epochs: Map<WasmlineEngineKind, Map<Int, Int>>,
        requestedBackends: Set<WasmlineEngineKind>,
    ): Map<WasmlineEngineKind, Set<Int>> = requestedBackends.associateWith { backend ->
        selectedGenerations.mapNotNull { generation -> epochs[backend]?.get(generation) }.toSet()
    }

    /** Returns the requested backends whose profile epoch is absent from the selection. */
    private fun affectedBackendsForRange(
        range: WasmlineAotReleaseRange,
        epochs: Map<WasmlineEngineKind, Map<Int, Int>>,
        selectedEpochs: Map<WasmlineEngineKind, Set<Int>>,
        requestedBackends: Set<WasmlineEngineKind>,
    ): List<WasmlineEngineKind> = requestedBackends.filter { backend ->
        val epoch = epochs[backend]?.get(range.aotGeneration)
        epoch != null && epoch !in selectedEpochs.getOrDefault(backend, emptySet())
    }

    private fun buildAffectedRanges(
        catalog: WasmlineAotReleaseCatalog,
        selectedGenerations: List<Int>,
        localCurrentGeneration: Int,
        requestedBackends: Set<WasmlineEngineKind>,
        epochs: Map<WasmlineEngineKind, Map<Int, Int>>,
        selectedEpochs: Map<WasmlineEngineKind, Set<Int>>,
    ): List<String> = catalog.ranges.mapIndexedNotNull { index, range ->
        val affected = affectedBackendsForRange(range, epochs, selectedEpochs, requestedBackends)
        val omittedKnownGeneration = range.aotGeneration <= localCurrentGeneration &&
            range.aotGeneration !in selectedGenerations
        val newerGeneration = range.aotGeneration > localCurrentGeneration
        if (affected.isEmpty() || (!omittedKnownGeneration && !newerGeneration)) {
            null
        } else {
            val next = catalog.ranges.getOrNull(index + 1)
            val end = next?.fromWasmlineVersion ?: catalog.currentWasmlineVersion
            val bound = if (next == null) {
                "from ${range.fromWasmlineVersion} through $end"
            } else {
                "from ${range.fromWasmlineVersion} to before $end"
            }
            "Wasmline $bound (AOT generation ${range.aotGeneration})"
        }
    }

    private fun formatWarning(code: String, report: WasmlineAotCompatibilityCheckReport, reportFile: File): String = buildString {
        if (code == WARNING_REVIEW) {
            appendLine("[$code] Wasmline AOT compatibility requires review.")
            appendLine("Selector: ${report.selector}")
            appendLine("Build Wasmline version: ${report.localWasmlineVersion}")
            appendLine("Latest published Wasmline version: ${report.latestPublishedWasmlineVersion}")
            appendLine("Selected AOT generations: ${report.selectedAotGenerations.joinToString().ifEmpty { "none" }}")
            appendLine("Latest published AOT generation: ${report.latestPublishedAotGeneration}")
            appendLine("AOT generation gap: ${report.aotGenerationGap}")
            appendLine("Newer AOT generations not included in this package: ${report.newerGenerationCount}")
            appendLine(
                if (report.affectedWasmlineRanges.isEmpty()) {
                    "No omitted AOT generation was found in the configured host range."
                } else {
                    "Affected host ranges: ${report.affectedWasmlineRanges.joinToString("; ")}"
                },
            )
            if (report.aotGenerationGap == 0 && report.omittedKnownGenerations.isEmpty()) {
                appendLine("This warning remains enabled so that a later Wasmline release with a new AOT generation is not missed.")
            }
            appendLine(
                "Affected backends: " + report.affectedBackends.joinToString().ifEmpty { "none" },
            )
            if (report.newerGenerationCount > 0 || report.omittedKnownGenerations.isNotEmpty()) {
                appendLine("Native hosts using omitted AOT profiles cannot load the corresponding CWASM or PWASM artifacts.")
            }
            appendLine("Web raw Wasm is not affected by this AOT check.")
            appendLine(
                "To acknowledge this result and suppress future warnings, set aotCompatibility.suppressCompatibilityWarning to true.",
            )
        } else {
            appendLine("[$code] The latest published Wasmline AOT compatibility could not be checked.")
            appendLine("The local catalog and selected profiles were validated successfully.")
            appendLine("Online status: unavailable")
            appendLine("Reason: ${report.remoteFailureReason ?: "request failed"}")
            appendLine("Local AOT generation: ${report.localCurrentAotGeneration}")
            appendLine("Selected AOT generations: ${report.selectedAotGenerations.joinToString().ifEmpty { "none" }}")
            appendLine("Web raw Wasm is not affected by this AOT check.")
            appendLine("Run ./gradlew wasmlineCheckAotCompatibility when network access is available.")
        }
        appendLine("Report: ${reportFile.absolutePath}")
        append("Documentation: ${report.documentationUrl}")
    }

    private fun writeReport(file: File, report: WasmlineAotCompatibilityCheckReport) {
        val parent = file.parentFile ?: error("AOT checker report has no parent directory.")
        check(parent.isDirectory || parent.mkdirs()) { "Unable to create AOT checker report directory." }
        val content = REPORT_JSON.encodeToString(report) + "\n"
        val temporary = Files.createTempFile(parent.toPath(), ".${file.name}-", ".tmp")
        try {
            Files.writeString(temporary, content)
            runCatching {
                Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun classifyFailure(error: Throwable): String = when (error) {
        is java.net.SocketTimeoutException -> "request timed out after 5 seconds"

        is java.net.UnknownHostException -> "DNS resolution failed"

        is IOException -> "network I/O failed"

        else -> when {
            error.message?.contains("without a valid cached catalog", ignoreCase = true) == true ->
                "remote catalog validation failed"

            error.message?.contains("checksum", ignoreCase = true) == true -> "checksum verification failed"

            error.message?.contains("schema", ignoreCase = true) == true -> "remote catalog schema is invalid"

            error.message?.contains(Regex("\\bHTTP\\s+\\d{3}\\b")) == true -> "remote server returned an error"

            error is SerializationException -> "remote catalog schema is invalid"

            else -> "remote catalog validation failed"
        }
    }

    /**
     * Contains the bounded result of resolving the optional remote catalog.
     *
     * Date: 2026-08-29
     * Author: crowforkotlin
     */
    private data class RemoteCatalogResult(
        val catalog: WasmlineAotReleaseCatalog?,
        val source: AotCompatibilityCatalogSource,
        val status: AotCompatibilityRemoteStatus,
        val failureReason: String? = null,
    )

    private companion object {
        const val WARNING_REVIEW: String = "WLAOT001"
        const val WARNING_REMOTE_UNAVAILABLE: String = "WLAOT002"
        const val REPORT_SCHEMA_VERSION: Int = 1
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)
        val CHECKSUM_PATTERN: Regex = Regex("(?i)[0-9a-f]{64}")
        val PROFILE_ID_PATTERN: Regex = Regex("^sha256:[0-9a-f]{64}$")
    }
}

private fun validateHttpsUri(uri: URI, description: String) {
    require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
        "$description must use HTTPS and include a host."
    }
    require(uri.userInfo == null) { "$description must not include user information." }
    require(uri.fragment == null) { "$description must not include a fragment." }
    require(uri.port <= 65535) { "$description has an invalid port." }
}

/**
 * Defines the small HTTP surface required by the advisory checker.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
interface AotCompatibilityHttpTransport {
    /** Fetches one HTTPS resource with bounded timeouts and response bytes. */
    fun fetch(url: String, headers: Map<String, String>, timeout: Duration): AotCompatibilityHttpResponse
}

/**
 * Contains one bounded HTTP response used by the checker.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
data class AotCompatibilityHttpResponse(
    val statusCode: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = byteArrayOf(),
) {
    /** Returns a response header without depending on server-side casing. */
    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
}

/**
 * Performs HTTPS requests without following more than one redirect.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
class JvmAotCompatibilityHttpTransport : AotCompatibilityHttpTransport {
    override fun fetch(url: String, headers: Map<String, String>, timeout: Duration): AotCompatibilityHttpResponse {
        var current = URI(url)
        validateHttpsUri(current, "AOT checker URL")
        var redirectCount = 0
        val deadline = System.nanoTime() + timeout.toNanos()
        while (true) {
            validateHttpsUri(current, "AOT checker URL")
            val remainingMillis = remainingTimeoutMillis(deadline)
            val connection = current.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = remainingMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                connection.readTimeout = minOf(remainingMillis, MAX_READ_WAIT_MILLIS)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
                connection.requestMethod = "GET"
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                val status = connection.responseCode
                if (status in 300..399 && status != HTTP_NOT_MODIFIED) {
                    val redirect = resolveAotCompatibilityRedirect(
                        current = current,
                        location = connection.getHeaderField("Location"),
                        redirectCount = redirectCount,
                    )
                    current = redirect.first
                    redirectCount = redirect.second
                    continue
                }
                val contentLength = connection.contentLengthLong
                require(contentLength < 0 || contentLength <= MAX_RESPONSE_BYTES) {
                    "remote catalog exceeds the response size limit"
                }
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val body = stream?.use { readBounded(it, MAX_RESPONSE_BYTES, deadline) } ?: byteArrayOf()
                val responseHeaders = connection.headerFields.entries
                    .filter { it.key != null }
                    .associate { it.key!! to it.value.firstOrNull().orEmpty() }
                return AotCompatibilityHttpResponse(status, responseHeaders, body)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun readBounded(stream: java.io.InputStream, limit: Int, deadline: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            checkDeadline(deadline)
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "remote catalog exceeds the response size limit" }
            output.write(buffer, 0, count)
            checkDeadline(deadline)
        }
        return output.toByteArray()
    }

    private fun remainingTimeoutMillis(deadline: Long): Long {
        val remainingNanos = deadline - System.nanoTime()
        if (remainingNanos <= 0) throw SocketTimeoutException(AOT_COMPATIBILITY_TIMEOUT_MESSAGE)
        return ((remainingNanos + 999_999L) / 1_000_000L).coerceAtLeast(1L)
    }

    private fun checkDeadline(deadline: Long) {
        if (System.nanoTime() >= deadline) throw SocketTimeoutException(AOT_COMPATIBILITY_TIMEOUT_MESSAGE)
    }

    private companion object {
        const val MAX_READ_WAIT_MILLIS: Long = 1_000L
    }
}

/** Resolves one HTTPS redirect while enforcing the one-hop policy. */
internal fun resolveAotCompatibilityRedirect(current: URI, location: String?, redirectCount: Int): Pair<URI, Int> {
    require(redirectCount == 0) { "AOT checker followed more than one redirect." }
    val resolved = current.resolve(requireNotNull(location) { "AOT checker redirect has no location." })
    validateHttpsUri(resolved, "AOT checker redirect")
    return resolved to 1
}

/**
 * Stores HTTP validators and freshness metadata for one cached catalog.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
private data class CacheMetadata(
    val fetchedAtMillis: Long = 0,
    val etag: String? = null,
    val lastModified: String? = null,
    val remoteUrl: String? = null,
)

/**
 * Couples a validated public catalog with its cache metadata.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
private data class CachedCatalog(val catalog: WasmlineAotReleaseCatalog, val metadata: CacheMetadata) {
    fun isExpired(now: Instant): Boolean =
        metadata.fetchedAtMillis <= 0 || now.toEpochMilli() - metadata.fetchedAtMillis >= CACHE_TTL_MILLIS
}

/**
 * Provides locked, content-addressed storage for one remote catalog URL.
 *
 * Date: 2026-08-29
 * Author: crowforkotlin
 */
private class CatalogCache(baseDirectory: File, private val remoteUrl: String) {
    private val directory = File(baseDirectory, sha256Hex(remoteUrl.encodeToByteArray()))
    private val catalogFile = File(directory, "aot-compatibility.json")
    private val checksumFile = File(directory, "aot-compatibility.json.sha256")
    private val metadataFile = File(directory, "metadata.properties")
    private val lockFile = File(directory, ".lock")

    fun read(): CachedCatalog? {
        if (!catalogFile.isFile || !checksumFile.isFile || !metadataFile.isFile) return null
        return runCatching {
            val body = catalogFile.readBytes()
            require(body.size <= MAX_RESPONSE_BYTES)
            val expected = checksumFile.readText().trim()
            require(expected == sha256Hex(body))
            val catalog = WasmlineAotReleaseCatalogCodec.decodePublic(body.decodeToString())
            val properties = Properties().apply {
                metadataFile.inputStream().use { input -> load(input) }
            }
            require(properties.getProperty("remoteUrl") == remoteUrl)
            CachedCatalog(
                catalog = catalog,
                metadata = CacheMetadata(
                    fetchedAtMillis = properties.getProperty("fetchedAtMillis")?.toLongOrNull() ?: 0,
                    etag = properties.getProperty("etag"),
                    lastModified = properties.getProperty("lastModified"),
                    remoteUrl = properties.getProperty("remoteUrl"),
                ),
            )
        }.getOrNull()
    }

    fun write(body: ByteArray, metadata: CacheMetadata) {
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create AOT compatibility cache directory." }
        atomicWrite(catalogFile, body)
        atomicWrite(checksumFile, sha256Hex(body).toByteArray())
        val properties = Properties().apply {
            setProperty("fetchedAtMillis", metadata.fetchedAtMillis.toString())
            metadata.etag?.let { setProperty("etag", it) }
            metadata.lastModified?.let { setProperty("lastModified", it) }
            setProperty("remoteUrl", remoteUrl)
        }
        val temporary = Files.createTempFile(directory.toPath(), ".metadata-", ".tmp")
        try {
            temporary.toFile().outputStream().use { properties.store(it, null) }
            runCatching {
                Files.move(temporary, metadataFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(temporary, metadataFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun touch(metadata: CacheMetadata, fetchedAtMillis: Long) {
        write(body = catalogFile.readBytes(), metadata = metadata.copy(fetchedAtMillis = fetchedAtMillis))
    }

    fun <T> withLock(deadline: Instant, now: () -> Instant, block: () -> T): T {
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create AOT compatibility cache directory." }
        return FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            runWithLock(channel, deadline, now, block)
        }
    }

    private fun <T> runWithLock(channel: FileChannel, deadline: Instant, now: () -> Instant, block: () -> T): T {
        while (true) {
            val acquired = runCatching { channel.tryLock() }.getOrNull()
            if (acquired != null) return acquired.use { block() }
            val remaining = Duration.between(now(), deadline).toMillis()
            if (remaining <= 0) throw SocketTimeoutException(AOT_COMPATIBILITY_TIMEOUT_MESSAGE)
            try {
                Thread.sleep(minOf(50L, remaining))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("AOT compatibility lock wait was interrupted", interrupted)
            }
        }
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        val temporary = Files.createTempFile(file.parentFile.toPath(), ".${file.name}-", ".tmp")
        try {
            Files.write(temporary, bytes)
            runCatching {
                Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

/** Returns the shared Gradle cache directory used by AOT compatibility checks. */
@InternalWasmlineToolingApi
fun defaultAotCompatibilityCacheDirectory(): File = File(
    System.getProperty("user.home"),
    ".gradle/caches/wasmline/aot-compatibility",
)

private fun sha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private const val CACHE_TTL_MILLIS: Long = 24L * 60L * 60L * 1000L
private const val MAX_RESPONSE_BYTES: Int = 256 * 1024
private const val HTTP_NOT_MODIFIED: Int = 304
private const val AOT_COMPATIBILITY_TIMEOUT_MESSAGE: String =
    "AOT compatibility request timed out after 5 seconds"
private val REPORT_JSON = Json {
    prettyPrint = true
    encodeDefaults = true
}
private const val DEFAULT_REMOTE_CATALOG_URL: String =
    "https://github.com/crowforkotlin/wasmline/releases/latest/download/aot-compatibility.json"
private const val DEFAULT_DOCUMENTATION_URL: String =
    "https://github.com/crowforkotlin/wasmline/blob/main/docs/content/docs/wasmtime-download.mdx"
