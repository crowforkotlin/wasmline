/**
 * Measures Wasmline invocation paths on the JVM host.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
package crow.wasmline.test.wasmtime

import crow.wasmline.*
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invocation.WasmlineErrorCode
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.math.roundToLong

object WasmlineInvocationBenchmark {
    private const val DEFAULT_WARMUP = 32
    private const val DEFAULT_ITERATIONS = 256

    @JvmStatic
    fun main(args: Array<String>) {
        val warmup = property("wasmline.benchmark.warmup", DEFAULT_WARMUP)
        val iterations = property("wasmline.benchmark.iterations", DEFAULT_ITERATIONS)
        require(warmup >= 0) { "Benchmark warmup must not be negative." }
        require(iterations > 0) { "Benchmark iterations must be positive." }

        var coreSuccess: Wasmline? = null
        var coreFailure: Wasmline? = null
        var raw: Wasmline? = null
        var component: Wasmline? = null
        wasmlineBootstrap()
        try {
            wasmlineWarmup(WasmlineWarmupMode.CRANELIFT)
            coreSuccess = loadCore(createCoreFixture(success = true))
            coreFailure = loadCore(createCoreFixture(success = false))
            raw = loadRaw(createRawFixture())
            component = loadComponent(copyComponentFixture())

            benchmark(
                name = "core_success",
                payloadBytes = 4,
                codecPasses = 2,
                warmup = warmup,
                iterations = iterations,
            ) {
                coreSuccess.callResult("benchmark.core.success", CORE_ADD_PAYLOAD)
            }
            benchmark(
                name = "core_failure",
                payloadBytes = 0,
                codecPasses = 2,
                warmup = warmup,
                iterations = iterations,
            ) {
                coreFailure.callResult("benchmark.core.failure")
            }

            benchmark(
                name = "raw_success",
                payloadBytes = 20,
                codecPasses = 4,
                warmup = warmup,
                iterations = iterations,
            ) {
                raw.invokeRawResult("add", listOf(WasmlineRawValue.I32(2), WasmlineRawValue.I32(3)))
            }
            benchmark(
                name = "raw_failure",
                payloadBytes = 4,
                codecPasses = 4,
                warmup = warmup,
                iterations = iterations,
            ) {
                raw.invokeRawResult("missing")
            }
            benchmark(
                name = "component_success",
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
                name = "component_failure",
                payloadBytes = 4,
                codecPasses = 4,
                warmup = warmup,
                iterations = iterations,
            ) {
                component.invokeComponentResult("missing")
            }
        } finally {
            component?.close()
            raw?.close()
            coreFailure?.close()
            coreSuccess?.close()
            wasmlineShutdown()
        }
    }

    private fun benchmark(
        name: String,
        payloadBytes: Int,
        codecPasses: Int,
        warmup: Int,
        iterations: Int,
        call: () -> WasmlineCallResult<*>,
    ) {
        repeat(warmup) { verify(name, call()) }
        val samples = LongArray(iterations)
        val allocatedBefore = allocatedBytes()
        repeat(iterations) { index ->
            val start = System.nanoTime()
            verify(name, call())
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
                "p50Ns=${percentile(samples, 50)} p95Ns=${percentile(samples, 95)} " +
                "p99Ns=${percentile(samples, 99)} allocationBytesPerCall=$allocationPerCall",
        )
    }

    private fun verify(name: String, result: WasmlineCallResult<*>) {
        when (name) {
            "core_success", "raw_success", "component_success" ->
                check(result is WasmlineCallResult.Success) { "$name returned $result" }

            "core_failure" -> checkFailure(result, WasmlineErrorCode.ACTION_NOT_BOUND)

            "raw_failure" -> checkFailure(result, WasmlineErrorCode.CORE_EXPORT_NOT_FOUND)

            "component_failure" -> checkFailure(result, WasmlineErrorCode.COMPONENT_EXPORT_NOT_FOUND)

            else -> error("Unknown benchmark case: $name")
        }
    }

    private fun checkFailure(result: WasmlineCallResult<*>, code: WasmlineErrorCode) {
        check(result is WasmlineCallResult.Failure) { "Expected $code but received $result" }
        check(result.error.code == code) { "Expected $code but received ${result.error.code}" }
    }

    private fun percentile(samples: LongArray, percentile: Int): Long {
        val index = ((samples.size - 1) * percentile / 100).coerceIn(0, samples.lastIndex)
        return samples[index]
    }

    private fun loadCore(file: File): Wasmline = load(
        WasmlineArtifactDescriptor(
            path = file.absolutePath,
            executionModel = WasmlineExecutionModel.CORE_WASM,
            invocationProtocol = WasmlineInvocationProtocol.WASMLINE_CORE,
        ),
    )

    private fun loadRaw(file: File): Wasmline = load(
        WasmlineArtifactDescriptor(
            path = file.absolutePath,
            executionModel = WasmlineExecutionModel.CORE_WASM,
            invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
            exportName = "add",
        ),
    )

    private fun loadComponent(file: File): Wasmline = load(
        WasmlineArtifactDescriptor(
            path = file.absolutePath,
            executionModel = WasmlineExecutionModel.COMPONENT_MODEL,
            invocationProtocol = WasmlineInvocationProtocol.COMPONENT_EXPORT,
            exportName = "add",
        ),
    )

    private fun load(descriptor: WasmlineArtifactDescriptor): Wasmline {
        val state = wasmlineLoadArtifact(descriptor, WasmlineConfig(supportConcurrent = false))
        return checkNotNull((state as? WasmlineLoadState.Success)?.wasmline) {
            "Benchmark artifact could not be loaded: $state"
        }
    }

    private fun allocatedBytes(): Long? {
        val bean = ManagementFactory.getThreadMXBean()
        val allocationBean = bean as? com.sun.management.ThreadMXBean ?: return null
        if (!allocationBean.isThreadAllocatedMemorySupported) return null
        if (!allocationBean.isThreadAllocatedMemoryEnabled) {
            runCatching { allocationBean.isThreadAllocatedMemoryEnabled = true }
        }
        if (!allocationBean.isThreadAllocatedMemoryEnabled) return null
        return allocationBean.getThreadAllocatedBytes(Thread.currentThread().threadId())
    }

    private fun property(name: String, default: Int): Int = System.getProperty(name)?.toIntOrNull() ?: default

    private fun createRawFixture(): File = File.createTempFile("wasmline-benchmark-raw-", ".wasm").apply {
        writeBytes(RAW_FIXTURE)
        deleteOnExit()
    }

    private fun copyComponentFixture(): File = File.createTempFile("wasmline-benchmark-component-", ".wasm").apply {
        WasmlineInvocationBenchmark::class.java.getResourceAsStream("/fixtures/component-export.wasm").use { input ->
            requireNotNull(input) { "Component fixture resource is missing." }
            outputStream().use { output -> input.copyTo(output) }
        }
        deleteOnExit()
    }

    private fun createCoreFixture(success: Boolean): File = File.createTempFile("wasmline-benchmark-core-", ".wasm").apply {
        writeBytes(createCoreModule(if (success) CORE_SUCCESS_FRAME else CORE_FAILURE_FRAME))
        deleteOnExit()
    }

    private fun createCoreModule(response: ByteArray): ByteArray {
        val typeSection = byteArrayOf(
            2,
            0x60, 2, 0x7F, 0x7F, 0,
            0x60, 2, 0x7F, 0x7F, 0,
        )
        val importSection = byteArrayOf(1) + wasmString("env") + wasmString("bridge_inbound_set_response") +
            byteArrayOf(0, 0)
        val functionSection = byteArrayOf(1, 1)
        val memorySection = byteArrayOf(1, 0, 1)
        val exportSection = byteArrayOf(2) +
            wasmString("memory") + byteArrayOf(2, 0) +
            wasmString("__wasmline_wasi_entry") + byteArrayOf(0, 1)
        val body = byteArrayOf(0, 0x41, 0, 0x41, response.size.toByte(), 0x10, 0, 0x0B)
        val codeSection = byteArrayOf(1, body.size.toByte()) + body
        val dataSection = byteArrayOf(1, 0, 0x41, 0, 0x0B, response.size.toByte()) + response

        return byteArrayOf(0, 0x61, 0x73, 0x6D, 1, 0, 0, 0) +
            wasmSection(1, typeSection) +
            wasmSection(2, importSection) +
            wasmSection(3, functionSection) +
            wasmSection(5, memorySection) +
            wasmSection(7, exportSection) +
            wasmSection(10, codeSection) +
            wasmSection(11, dataSection)
    }

    private fun wasmString(value: String): ByteArray {
        val bytes = value.encodeToByteArray()
        return byteArrayOf(bytes.size.toByte()) + bytes
    }

    private fun wasmSection(id: Int, payload: ByteArray): ByteArray = byteArrayOf(id.toByte(), payload.size.toByte()) + payload

    private val CORE_ADD_PAYLOAD = byteArrayOf(8, 2, 16, 3)
    private val CORE_SUCCESS_FRAME = ByteArray(18).apply {
        set(0, 0x57)
        set(1, 0x4C)
        set(2, 0x4D)
        set(3, 0x46)
        set(4, 1)
    }
    private val CORE_FAILURE_FRAME = ByteArray(46).apply {
        set(0, 0x57)
        set(1, 0x4C)
        set(2, 0x4D)
        set(3, 0x46)
        set(4, 1)
        set(5, 1)
        set(6, 0xE9.toByte())
        set(7, 3)
        set(10, 28)
        "No Wasmline action is bound.".encodeToByteArray().copyInto(this, 18)
    }

    private val RAW_FIXTURE = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x0B, 0x02, 0x60, 0x02, 0x7F, 0x7F, 0x01, 0x7F, 0x60, 0x00, 0x01, 0x7F,
        0x03, 0x03, 0x02, 0x00, 0x01,
        0x07, 0x0E, 0x02, 0x03, 0x61, 0x64, 0x64, 0x00, 0x00, 0x04, 0x74, 0x72, 0x61, 0x70, 0x00, 0x01,
        0x0A, 0x0D, 0x02, 0x07, 0x00, 0x20, 0x00, 0x20, 0x01, 0x6A, 0x0B, 0x03, 0x00, 0x00, 0x0B,
    )
}
