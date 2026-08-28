package crow.wasmline.plugin.core.aot

import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import kotlinx.serialization.Serializable

/**
 * Freezes every Wasmtime option that participates in AOT compatibility identity.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@Serializable
@InternalWasmlineToolingApi
data class WasmlineAotCompileOptions(
    val schemaVersion: Int = 1,
    val componentModel: Boolean = true,
    val collector: String = "drc",
    val gc: Boolean = true,
    val gcSupport: Boolean = true,
    val referenceTypes: Boolean = true,
    val functionReferences: Boolean = true,
    val exceptions: Boolean = true,
    val threads: Boolean = false,
    val simd: Boolean = false,
    val relaxedSimd: Boolean = false,
    val concurrencySupport: Boolean = true,
    val maxWasmStack: Long = 512L * 1024L,
    val memoryGuardSize: Long = 0,
    val signalsBasedTraps: Boolean = false,
    val optimizationLevel: Int = 0,
    val craneliftDebugVerifier: Boolean = false,
) {
    init {
        require(canonicalDescriptor() == FROZEN_DESCRIPTOR) {
            "AOT compile options must match the frozen Wasmline schema-$schemaVersion profile."
        }
    }

    /** Returns the catalog descriptor covered by the compatibility profile ID. */
    fun canonicalDescriptor(): String = listOf(
        "wasmline-aot-v$schemaVersion",
        "component-model=${componentModel.yesNo()}",
        "collector=$collector",
        "gc=${gc.yesNo()}",
        "gc-support=${gcSupport.yesNo()}",
        "reference-types=${referenceTypes.yesNo()}",
        "function-references=${functionReferences.yesNo()}",
        "exceptions=${exceptions.yesNo()}",
        "threads=${threads.yesNo()}",
        "simd=${simd.yesNo()}",
        "relaxed-simd=${relaxedSimd.yesNo()}",
        "concurrency-support=${concurrencySupport.yesNo()}",
        "max-wasm-stack=$maxWasmStack",
        "memory-guard-size=$memoryGuardSize",
        "signals-based-traps=${signalsBasedTraps.yesNo()}",
        "opt-level=$optimizationLevel",
        "cranelift-debug-verifier=${craneliftDebugVerifier.yesNo()}",
    ).joinToString(";")

    /** Returns the frozen Wasmtime CLI arguments for one input, output, and target. */
    fun compilerArguments(inputFile: String, outputFile: String, normalizedTarget: String): List<String> = listOf(
        "compile",
        inputFile,
        "-o",
        outputFile,
        "--target",
        normalizedTarget,
        "-W",
        "component-model=${componentModel.yesNo()}",
        "-C",
        "collector=$collector",
        "-W",
        "gc=${gc.yesNo()}",
        "-W",
        "gc-support=${gcSupport.yesNo()}",
        "-W",
        "reference-types=${referenceTypes.yesNo()}",
        "-W",
        "function-references=${functionReferences.yesNo()}",
        "-W",
        "exceptions=${exceptions.yesNo()}",
        "-W",
        "threads=${threads.yesNo()}",
        "-W",
        "simd=${simd.yesNo()}",
        "-W",
        "relaxed-simd=${relaxedSimd.yesNo()}",
        "-W",
        "concurrency-support=${concurrencySupport.yesNo()}",
        "-W",
        "max-wasm-stack=$maxWasmStack",
        "-O",
        "memory-guard-size=$memoryGuardSize",
        "-O",
        "signals-based-traps=${signalsBasedTraps.yesNo()}",
        "-O",
        "opt-level=$optimizationLevel",
        "-C",
        "cranelift-debug-verifier=${craneliftDebugVerifier.yesNo(trueValue = "yes", falseValue = "no")}",
    )

    private fun Boolean.yesNo(trueValue: String = "y", falseValue: String = "n"): String = if (this) trueValue else falseValue

    /**
     * Defines the frozen compatibility descriptor for the current compile schema.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    companion object {
        const val FROZEN_DESCRIPTOR: String =
            "wasmline-aot-v1;component-model=y;collector=drc;gc=y;gc-support=y;reference-types=y;" +
                "function-references=y;exceptions=y;threads=n;simd=n;relaxed-simd=n;concurrency-support=y;" +
                "max-wasm-stack=524288;memory-guard-size=0;signals-based-traps=n;opt-level=0;" +
                "cranelift-debug-verifier=n"
    }
}
