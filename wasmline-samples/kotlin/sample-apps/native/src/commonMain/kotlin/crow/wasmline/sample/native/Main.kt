package crow.wasmline.sample.native

import crow.wasmline.WasmlineLoadResult
import crow.wasmline.WasmlineRawValue
import crow.wasmline.WasmlineRuntime
import crow.wasmline.invocation.WasmlineCallResult
import crow.wasmline.invokeRawResult
import crow.wasmline.loader.WasmlineLoadOptions
import crow.wasmline.loader.WasmlineLoader
import crow.wasmline.loader.WasmlineTrustedKeySet
import kotlinx.coroutines.runBlocking

private val sampleTrustedKeys = WasmlineTrustedKeySet.Builder()
    .addHex(
        algorithm = "Ed25519",
        keyId = null,
        publicKeyHex = "5a778289bee0c57b05a1c48c8ef312da6ce8e4e4f13fc1a2e8e5aa4cde7ae0db",
    )
    .build()

fun main(args: Array<String>) = runBlocking {
    val manifestPath = requireNotNull(args.firstOrNull()?.takeIf(String::isNotBlank)) {
        "Usage: wasmline-native-sample <manifest.wlm> [left] [right] [pulley|cranelift]"
    }
    val left = args.getOrNull(1)?.toIntOrNull() ?: 19
    val right = args.getOrNull(2)?.toIntOrNull() ?: 23
    val expectedBackend = args.getOrNull(3)?.lowercase()

    val runtimeInfo = requireNotNull(WasmlineRuntime.nativeInfo()) {
        "The statically linked Wasmline Native runtime is unavailable."
    }
    if (expectedBackend != null) {
        check(runtimeInfo.backend.name.lowercase() == expectedBackend) {
            "Expected the $expectedBackend backend, but linked ${runtimeInfo.backend.name.lowercase()}."
        }
    }

    println(
        "[Kotlin/Native] backend=${runtimeInfo.backend.name.lowercase()} " +
            "target=${runtimeInfo.operatingSystem}/${runtimeInfo.architecture} " +
            "wasmtime=${runtimeInfo.wasmtimeVersion}",
    )
    println("[Kotlin/Native] loading signed package: $manifestPath")

    try {
        val loaded = WasmlineLoader.load(
            source = manifestPath,
            options = WasmlineLoadOptions(trustedKeys = sampleTrustedKeys),
        )
        val wasmline = when (loaded) {
            is WasmlineLoadResult.Failure -> error("Failed to load the sample package: ${loaded.failure.message}")
            is WasmlineLoadResult.Success -> loaded.wasmline
        }

        try {
            val invocation = wasmline.invokeRawResult(
                exportName = "add_i32",
                arguments = listOf(
                    WasmlineRawValue.I32(left),
                    WasmlineRawValue.I32(right),
                ),
            )
            val actual = when (invocation) {
                is WasmlineCallResult.Failure -> error(
                    "Raw Export invocation failed: ${invocation.failure.code}: ${invocation.failure.message}",
                )

                is WasmlineCallResult.Success ->
                    (invocation.value.values.singleOrNull() as? WasmlineRawValue.I32)?.value
                        ?: error("add_i32 returned an unexpected value: ${invocation.value.values}")
            }
            val expected = left + right
            check(actual == expected) { "add_i32($left, $right) returned $actual; expected $expected." }
            println("[Kotlin/Native] PASS add_i32($left, $right) = $actual")
        } finally {
            wasmline.close()
        }
    } finally {
        WasmlineRuntime.shutdown()
    }
}
