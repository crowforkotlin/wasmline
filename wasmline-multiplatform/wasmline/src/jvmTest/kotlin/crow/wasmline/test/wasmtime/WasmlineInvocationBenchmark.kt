package crow.wasmline.test.wasmtime

import crow.wasmline.Wasmline
import crow.wasmline.WasmlineArtifactDescriptor
import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineComponentValue
import crow.wasmline.WasmlineConfig
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.WasmlineLoadState
import crow.wasmline.WasmlineRawValue
import crow.wasmline.WasmlineRuntime
import crow.wasmline.callResult
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import crow.wasmline.invokeComponentResult
import crow.wasmline.invokeRawResult
import crow.wasmline.platformWasmlineLoadArtifact
import crow.wasmline.platformWasmlineRuntimeCapabilities
import crow.wasmline.wasmlineAotLoadPathDiagnostics
import crow.wasmline.wasmlineResetAotLoadPathDiagnostics
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToLong

/**
 * Measures AOT-only Wasmline invocation and cold native loading paths on the JVM host.
 *
 * Runs two distinct benchmark modes:
 *
 * - `invocation`: measures warm calls using externally supplied `.cwasm` or `.pwasm` inputs.
 * - `cold-load`: forks one fresh JVM per sample and reports native load time plus process `VmHWM`.
 *
 * Raw Wasm is deliberately not accepted by either mode. Any compile-time baseline belongs to an
 * external Wasmtime CLI process and must never be routed through the JNI runtime.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
object WasmlineInvocationBenchmark {
    private const val DEFAULT_WARMUP = 32
    private const val DEFAULT_ITERATIONS = 256
    private const val DEFAULT_COLD_SAMPLES = 5

    private const val MODE_PROPERTY = "wasmline.benchmark.mode"
    private const val INVOCATION_MODE = "invocation"
    private const val COLD_LOAD_MODE = "cold-load"
    private const val COLD_LOAD_CHILD_MODE = "cold-load-child"
    private const val WARMUP_PROPERTY = "wasmline.benchmark.warmup"
    private const val ITERATIONS_PROPERTY = "wasmline.benchmark.iterations"
    private const val COLD_SAMPLES_PROPERTY = "wasmline.benchmark.coldSamples"
    private const val SUPPORT_CONCURRENT_PROPERTY = "wasmline.benchmark.supportConcurrent"
    private const val WASMLINE_SERVICE_AOT_PROPERTY = "wasmline.benchmark.wasmlineCoreAot"
    private const val RAW_EXPORT_AOT_PROPERTY = "wasmline.benchmark.rawExportAot"
    private const val COMPONENT_AOT_PROPERTY = "wasmline.benchmark.componentAot"
    private const val COLD_ARTIFACT_PATH_PROPERTY = "wasmline.benchmark.cold.artifactPath"
    private const val COLD_ARTIFACT_FORMAT_PROPERTY = "wasmline.benchmark.cold.artifactFormat"
    private const val COLD_ARTIFACT_KIND_PROPERTY = "wasmline.benchmark.cold.artifactKind"
    private const val COLD_LOAD_PREFIX = "WASMLINE_COLD_LOAD "
    private val VmHwmPattern = Regex("(?m)^VmHWM:\\s+(\\d+)\\s+kB\\s*$")
    private val CORE_ADD_PAYLOAD = byteArrayOf(8, 2, 16, 3)

    @JvmStatic
    fun main(args: Array<String>) {
        when (stringProperty(MODE_PROPERTY, INVOCATION_MODE)) {
            INVOCATION_MODE -> runInvocationBenchmark()

            COLD_LOAD_MODE -> runColdLoadBenchmark()

            COLD_LOAD_CHILD_MODE -> runColdLoadChild()

            else -> error(
                "Unsupported $MODE_PROPERTY='${stringProperty(MODE_PROPERTY, "")}'. " +
                    "Expected $INVOCATION_MODE, $COLD_LOAD_MODE, or $COLD_LOAD_CHILD_MODE.",
            )
        }
    }

    private fun runInvocationBenchmark() {
        val warmup = intProperty(WARMUP_PROPERTY, DEFAULT_WARMUP)
        val iterations = intProperty(ITERATIONS_PROPERTY, DEFAULT_ITERATIONS)
        require(warmup >= 0) { "Benchmark warmup must not be negative." }
        require(iterations > 0) { "Benchmark iterations must be positive." }

        val wasmlineCore = optionalAotArtifact(
            kind = ArtifactKind.WASMLINE_SERVICE,
            propertyName = WASMLINE_SERVICE_AOT_PROPERTY,
        )
        val rawExportArtifact = requiredAotArtifact(
            kind = ArtifactKind.RAW_EXPORT,
            propertyName = RAW_EXPORT_AOT_PROPERTY,
        )
        val componentArtifact = requiredAotArtifact(
            kind = ArtifactKind.COMPONENT_EXPORT,
            propertyName = COMPONENT_AOT_PROPERTY,
        )

        var wasmlineCoreHandle: Wasmline? = null
        var rawExportHandle: Wasmline? = null
        var componentHandle: Wasmline? = null
        WasmlineRuntime.preload()
        try {
            wasmlineCoreHandle = wasmlineCore?.let(::load)
            val rawExport = load(rawExportArtifact)
            rawExportHandle = rawExport
            val component = load(componentArtifact)
            componentHandle = component

            wasmlineCoreHandle?.let { handle ->
                benchmark(
                    name = "wasmline_core_success",
                    payloadBytes = CORE_ADD_PAYLOAD.size,
                    codecPasses = 2,
                    warmup = warmup,
                    iterations = iterations,
                ) {
                    handle.callResult("benchmark.core.success", CORE_ADD_PAYLOAD)
                }
            }
            benchmark(
                name = "raw_export_success",
                payloadBytes = 20,
                codecPasses = 4,
                warmup = warmup,
                iterations = iterations,
            ) {
                rawExport.invokeRawResult("add", listOf(WasmlineRawValue.I32(2), WasmlineRawValue.I32(3)))
            }
            benchmark(
                name = "raw_export_failure",
                payloadBytes = 4,
                codecPasses = 4,
                warmup = warmup,
                iterations = iterations,
            ) {
                rawExport.invokeRawResult("missing")
            }
            benchmark(
                name = "component_export_success",
                payloadBytes = 9,
                codecPasses = 4,
                warmup = warmup,
                iterations = iterations,
            ) {
                component.invokeComponentResult(
                    "add",
                    listOf(WasmlineComponentValue.S32(2), WasmlineComponentValue.S32(3)),
                )
            }
            benchmark(
                name = "component_export_failure",
                payloadBytes = 4,
                codecPasses = 4,
                warmup = warmup,
                iterations = iterations,
            ) {
                component.invokeComponentResult("missing")
            }
        } finally {
            componentHandle?.close()
            rawExportHandle?.close()
            wasmlineCoreHandle?.close()
            WasmlineRuntime.shutdown()
        }
    }

    private fun runColdLoadBenchmark() {
        val samples = intProperty(COLD_SAMPLES_PROPERTY, DEFAULT_COLD_SAMPLES)
        require(samples > 0) { "Cold-load sample count must be positive." }

        val artifacts = buildList {
            optionalAotArtifact(ArtifactKind.WASMLINE_SERVICE, WASMLINE_SERVICE_AOT_PROPERTY)?.let(::add)
            add(requiredAotArtifact(ArtifactKind.RAW_EXPORT, RAW_EXPORT_AOT_PROPERTY))
            add(requiredAotArtifact(ArtifactKind.COMPONENT_EXPORT, COMPONENT_AOT_PROPERTY))
        }
        artifacts.forEach { artifact ->
            println(
                "WASMLINE_COLD_LOAD_INPUT kind=${artifact.kind.label} format=${artifact.format.name} " +
                    "sha256=${sha256Hex(artifact.file)} path=${artifact.file.absolutePath}",
            )
            val measurements = List(samples) { sampleIndex ->
                val processStart = System.nanoTime()
                val child = startColdLoadChild(artifact)
                val output = child.inputStream.bufferedReader().use { it.readText() }
                check(child.waitFor() == 0) {
                    "Cold-load child failed for ${artifact.kind.label} (${artifact.file.absolutePath}):\n" + output.takeLast(8_000)
                }
                val processElapsedNs = System.nanoTime() - processStart
                val childMeasurement = output.lineSequence()
                    .mapNotNull(::parseColdLoadRecord)
                    .lastOrNull()
                    ?: error(
                        "Cold-load child did not emit a measurement for ${artifact.kind.label}:\n" + output.takeLast(8_000),
                    )
                require(childMeasurement.kind == artifact.kind) {
                    "Cold-load child reported ${childMeasurement.kind.label}, expected ${artifact.kind.label}."
                }
                require(childMeasurement.format == artifact.format) {
                    "Cold-load child reported ${childMeasurement.format}, expected ${artifact.format}."
                }
                val measurement = childMeasurement.copy(processElapsedNs = processElapsedNs)
                println(
                    "WASMLINE_COLD_LOAD_SAMPLE kind=${measurement.kind.label} format=${measurement.format.name} " +
                        "sample=${sampleIndex + 1} nativeLoadElapsedNs=${measurement.nativeLoadElapsedNs} " +
                        "processElapsedNs=${measurement.processElapsedNs} peakRssKiB=${measurement.peakRssKiB} " +
                        "coreDeserialize=${measurement.coreDeserialize} componentDeserialize=${measurement.componentDeserialize} " +
                        "moduleNew=${measurement.moduleNew} componentNew=${measurement.componentNew}",
                )
                measurement
            }
            printColdLoadSummary(artifact, measurements)
        }
    }

    private fun startColdLoadChild(artifact: AotArtifact): Process {
        val command = mutableListOf(
            javaExecutable(),
            "-cp",
            requireNotNull(System.getProperty("java.class.path")) { "java.class.path is unavailable." },
            "-D$MODE_PROPERTY=$COLD_LOAD_CHILD_MODE",
            "-D$COLD_ARTIFACT_PATH_PROPERTY=${artifact.file.absolutePath}",
            "-D$COLD_ARTIFACT_FORMAT_PROPERTY=${artifact.format.name}",
            "-D$COLD_ARTIFACT_KIND_PROPERTY=${artifact.kind.label}",
            "-D$SUPPORT_CONCURRENT_PROPERTY=${supportConcurrent()}",
            WasmlineInvocationBenchmark::class.java.name,
        )
        return ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
    }

    private fun runColdLoadChild() {
        val artifact = coldChildArtifact()
        val start = System.nanoTime()
        var handle: Wasmline? = null
        try {
            WasmlineRuntime.preload()
            wasmlineResetAotLoadPathDiagnostics()
            handle = load(artifact)
            val nativeLoadElapsedNs = System.nanoTime() - start
            val diagnostics = wasmlineAotLoadPathDiagnostics()
            verifyDeserializeOnly(artifact, diagnostics)
            val peakRssKiB = requireNotNull(parseLinuxPeakRssKiB(File("/proc/self/status").readText())) {
                "Linux /proc/self/status did not contain VmHWM."
            }
            println(
                "WASMLINE_COLD_LOAD kind=${artifact.kind.label} format=${artifact.format.name} " +
                    "nativeLoadElapsedNs=$nativeLoadElapsedNs peakRssKiB=$peakRssKiB " +
                    "coreDeserialize=${diagnostics.coreDeserializeSuccesses} " +
                    "componentDeserialize=${diagnostics.componentDeserializeSuccesses} " +
                    "moduleNew=${diagnostics.moduleNewCalls} componentNew=${diagnostics.componentNewCalls}",
            )
        } finally {
            handle?.close()
            WasmlineRuntime.shutdown()
        }
    }

    private fun coldChildArtifact(): AotArtifact {
        val kind = ArtifactKind.fromLabel(requireProperty(COLD_ARTIFACT_KIND_PROPERTY))
        val file = File(requireProperty(COLD_ARTIFACT_PATH_PROPERTY))
        require(file.isFile) { "Cold-load artifact does not exist: ${file.absolutePath}" }
        val format = aotFormat(file.name)
        val requestedFormat = runCatching {
            WasmlineArtifactFormat.valueOf(requireProperty(COLD_ARTIFACT_FORMAT_PROPERTY))
        }.getOrElse { error("Cold-load artifact format must be CWASM or PWASM.") }
        require(format == requestedFormat) {
            "Cold-load artifact suffix format $format does not match requested $requestedFormat."
        }
        return AotArtifact(kind, file, format)
    }

    private fun verifyDeserializeOnly(artifact: AotArtifact, diagnostics: crow.wasmline.WasmlineAotLoadPathDiagnostics) {
        require(diagnostics.moduleNewCalls == 0) {
            "Cold-load benchmark observed forbidden wasmtime_module_new calls: ${diagnostics.moduleNewCalls}."
        }
        require(diagnostics.componentNewCalls == 0) {
            "Cold-load benchmark observed forbidden wasmtime_component_new calls: ${diagnostics.componentNewCalls}."
        }
        when (artifact.kind.executionModel) {
            WasmlineExecutionModel.CORE_WASM -> {
                require(diagnostics.coreDeserializeSuccesses == 1) {
                    "Expected one Core AOT deserialize, got ${diagnostics.coreDeserializeSuccesses}."
                }
                require(diagnostics.componentDeserializeSuccesses == 0) {
                    "Core AOT load unexpectedly deserialized a Component."
                }
            }

            WasmlineExecutionModel.COMPONENT_MODEL -> {
                require(diagnostics.coreDeserializeSuccesses == 0) {
                    "Component AOT load unexpectedly deserialized a Core module."
                }
                require(diagnostics.componentDeserializeSuccesses == 1) {
                    "Expected one Component AOT deserialize, got ${diagnostics.componentDeserializeSuccesses}."
                }
            }
        }
    }

    private fun printColdLoadSummary(artifact: AotArtifact, measurements: List<ColdLoadMeasurement>) {
        println(
            "WASMLINE_COLD_LOAD_SUMMARY kind=${artifact.kind.label} format=${artifact.format.name} " +
                "samples=${measurements.size} nativeLoadP50Ns=${percentile(measurements.map { it.nativeLoadElapsedNs }, 50)} " +
                "nativeLoadP95Ns=${percentile(measurements.map { it.nativeLoadElapsedNs }, 95)} " +
                "processP50Ns=${percentile(measurements.map { it.processElapsedNs }, 50)} " +
                "processP95Ns=${percentile(measurements.map { it.processElapsedNs }, 95)} " +
                "peakRssP50KiB=${percentile(measurements.map { it.peakRssKiB }, 50)} " +
                "peakRssMaxKiB=${measurements.maxOf { it.peakRssKiB }}",
        )
    }

    private fun benchmark(
        name: String,
        payloadBytes: Int,
        codecPasses: Int,
        warmup: Int,
        iterations: Int,
        call: () -> WasmlineCallResult<*>,
    ) {
        repeat(warmup) { verifyInvocation(name, call()) }
        val samples = LongArray(iterations)
        val allocatedBefore = allocatedBytes()
        repeat(iterations) { index ->
            val start = System.nanoTime()
            verifyInvocation(name, call())
            samples[index] = System.nanoTime() - start
        }
        val allocatedAfter = allocatedBytes()
        samples.sort()

        val allocationPerCall = if (allocatedBefore != null && allocatedAfter != null) {
            ((allocatedAfter - allocatedBefore).toDouble() / iterations).roundToLong().toString()
        } else {
            "unavailable"
        }
        println(
            "WASMLINE_BENCHMARK name=$name status=ok iterations=$iterations " +
                "payloadBytes=$payloadBytes codecPasses=$codecPasses " +
                "p50Ns=${percentile(samples.toList(), 50)} p95Ns=${percentile(samples.toList(), 95)} " +
                "p99Ns=${percentile(samples.toList(), 99)} allocationBytesPerCall=$allocationPerCall",
        )
    }

    private fun verifyInvocation(name: String, result: WasmlineCallResult<*>) {
        when (name) {
            "wasmline_core_success", "raw_export_success", "component_export_success" ->
                check(result is WasmlineCallResult.Success) { "$name returned $result" }

            "raw_export_failure" -> checkFailure(result, WasmlineErrorCode.CORE_EXPORT_NOT_FOUND)

            "component_export_failure" -> checkFailure(result, WasmlineErrorCode.COMPONENT_EXPORT_NOT_FOUND)

            else -> error("Unknown benchmark case: $name")
        }
    }

    private fun checkFailure(result: WasmlineCallResult<*>, code: WasmlineErrorCode) {
        check(result is WasmlineCallResult.Failure) { "Expected $code but received $result" }
        check(result.error.code == code) { "Expected $code but received ${result.error.code}" }
    }

    private fun load(artifact: AotArtifact): Wasmline {
        val runtime = platformWasmlineRuntimeCapabilities()
        val state = platformWasmlineLoadArtifact(
            descriptor = WasmlineArtifactDescriptor(
                path = artifact.file.absolutePath,
                artifactFormat = artifact.format,
                targetCpu = targetCpuFor(artifact.format, runtime.is64Bit, runtime.targetCpu),
                targetOs = targetOsFor(artifact.format, runtime.targetOs),
                targetCompilerVersion = "wasmtime-${runtime.wasmtimeVersion}",
                is64Bit = runtime.is64Bit,
                executionModel = artifact.kind.executionModel,
                invocationProtocol = artifact.kind.invocationProtocol,
                exportName = artifact.kind.exportName,
            ),
            config = WasmlineConfig(supportConcurrent = supportConcurrent()),
        )
        return checkNotNull((state as? WasmlineLoadState.Success)?.wasmline) {
            "Benchmark artifact could not be loaded: $state"
        }
    }

    private fun requiredAotArtifact(kind: ArtifactKind, propertyName: String): AotArtifact =
        artifactFromProperty(kind, propertyName, required = true)!!

    private fun optionalAotArtifact(kind: ArtifactKind, propertyName: String): AotArtifact? =
        artifactFromProperty(kind, propertyName, required = false)

    private fun artifactFromProperty(kind: ArtifactKind, propertyName: String, required: Boolean): AotArtifact? {
        val configured = System.getProperty(propertyName)?.trim().orEmpty()
        if (configured.isEmpty()) {
            require(!required) {
                "$propertyName must point to a precompiled .cwasm or .pwasm artifact."
            }
            return null
        }
        val file = File(configured)
        require(file.isFile) { "$propertyName does not point to a file: ${file.absolutePath}" }
        return AotArtifact(kind, file, aotFormat(file.name))
    }

    internal fun aotFormat(filename: String): WasmlineArtifactFormat = when {
        filename.endsWith(".cwasm", ignoreCase = true) -> WasmlineArtifactFormat.CWASM

        filename.endsWith(".pwasm", ignoreCase = true) -> WasmlineArtifactFormat.PWASM

        else -> throw IllegalArgumentException(
            "Benchmark artifacts must be precompiled .cwasm or .pwasm files, not '$filename'.",
        )
    }

    internal fun parseLinuxPeakRssKiB(status: String): Long? = VmHwmPattern.find(status)
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()

    private fun supportConcurrent(): Boolean = when (stringProperty(SUPPORT_CONCURRENT_PROPERTY, "false")) {
        "true" -> true
        "false" -> false
        else -> error("$SUPPORT_CONCURRENT_PROPERTY must be true or false.")
    }

    private fun targetCpuFor(artifactFormat: WasmlineArtifactFormat, is64Bit: Boolean, runtimeCpu: String): String = when (artifactFormat) {
        WasmlineArtifactFormat.CWASM -> runtimeCpu
        WasmlineArtifactFormat.PWASM -> if (is64Bit) "pulley64" else "pulley32"
        WasmlineArtifactFormat.RAW_WASM -> error("Native benchmark artifacts cannot use raw Wasm.")
    }

    private fun targetOsFor(artifactFormat: WasmlineArtifactFormat, runtimeOs: String): String? = when (artifactFormat) {
        WasmlineArtifactFormat.CWASM -> runtimeOs
        WasmlineArtifactFormat.PWASM -> null
        WasmlineArtifactFormat.RAW_WASM -> error("Native benchmark artifacts cannot use raw Wasm.")
    }

    private fun allocatedBytes(): Long? {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean()
        val allocationBean = bean as? com.sun.management.ThreadMXBean ?: return null
        if (!allocationBean.isThreadAllocatedMemorySupported) return null
        if (!allocationBean.isThreadAllocatedMemoryEnabled) {
            runCatching { allocationBean.isThreadAllocatedMemoryEnabled = true }
        }
        if (!allocationBean.isThreadAllocatedMemoryEnabled) return null
        return allocationBean.getThreadAllocatedBytes(Thread.currentThread().threadId())
    }

    private fun javaExecutable(): String {
        val suffix = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) ".exe" else ""
        return File(System.getProperty("java.home"), "bin/java$suffix").absolutePath
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead < 0) break
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun parseColdLoadRecord(line: String): ColdLoadMeasurement? {
        if (!line.startsWith(COLD_LOAD_PREFIX)) return null
        val values = line.removePrefix(COLD_LOAD_PREFIX)
            .split(' ')
            .filter(String::isNotBlank)
            .associate { field ->
                val separator = field.indexOf('=')
                require(separator > 0) { "Malformed cold-load field '$field'." }
                field.substring(0, separator) to field.substring(separator + 1)
            }
        return ColdLoadMeasurement(
            kind = ArtifactKind.fromLabel(values.required("kind")),
            format = WasmlineArtifactFormat.valueOf(values.required("format")),
            nativeLoadElapsedNs = values.required("nativeLoadElapsedNs").toLong(),
            peakRssKiB = values.required("peakRssKiB").toLong(),
            coreDeserialize = values.required("coreDeserialize").toInt(),
            componentDeserialize = values.required("componentDeserialize").toInt(),
            moduleNew = values.required("moduleNew").toInt(),
            componentNew = values.required("componentNew").toInt(),
        )
    }

    private fun Map<String, String>.required(name: String): String = requireNotNull(get(name)) {
        "Cold-load measurement is missing '$name'."
    }

    private fun percentile(samples: List<Long>, percentile: Int): Long {
        require(samples.isNotEmpty()) { "Cannot calculate a percentile from no samples." }
        val sorted = samples.sorted()
        val index = ((sorted.size - 1) * percentile / 100).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun intProperty(name: String, default: Int): Int = stringProperty(name, default.toString()).toIntOrNull() ?: default

    private fun stringProperty(name: String, default: String): String = System.getProperty(name)?.trim().orEmpty().ifEmpty { default }

    private fun requireProperty(name: String): String = requireNotNull(System.getProperty(name)?.trim()?.takeIf(String::isNotEmpty)) {
        "$name is required."
    }

    private data class AotArtifact(val kind: ArtifactKind, val file: File, val format: WasmlineArtifactFormat)

    private data class ColdLoadMeasurement(
        val kind: ArtifactKind,
        val format: WasmlineArtifactFormat,
        val nativeLoadElapsedNs: Long,
        val peakRssKiB: Long,
        val coreDeserialize: Int,
        val componentDeserialize: Int,
        val moduleNew: Int,
        val componentNew: Int,
        val processElapsedNs: Long = 0,
    )

    private enum class ArtifactKind(
        val label: String,
        val executionModel: WasmlineExecutionModel,
        val invocationProtocol: WasmlineInvocationProtocol,
        val exportName: String?,
    ) {
        WASMLINE_SERVICE(
            label = "wasmline-core",
            executionModel = WasmlineExecutionModel.CORE_WASM,
            invocationProtocol = WasmlineInvocationProtocol.WASMLINE_SERVICE,
            exportName = null,
        ),
        RAW_EXPORT(
            label = "raw-export",
            executionModel = WasmlineExecutionModel.CORE_WASM,
            invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
            exportName = "add",
        ),
        COMPONENT_EXPORT(
            label = "component-export",
            executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
            invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
            exportName = "add",
        ),
        ;

        companion object {
            fun fromLabel(label: String): ArtifactKind = entries.firstOrNull { it.label == label }
                ?: error("Unknown cold-load artifact kind '$label'.")
        }
    }
}
