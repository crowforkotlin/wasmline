package crow.wasmline.internal.core

import crow.wasmline.RawFunctionSignature
import crow.wasmline.RawImport
import crow.wasmline.RawValue
import crow.wasmline.RawValueType
import crow.wasmline.invocation.WasmlineCallResult
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoreWasmNativeCodecTest {
    @Test
    fun rejectsAnImportTextBeyondTheBridgeLimit() {
        val failure = assertFailsWith<IllegalArgumentException> {
            CoreWasmNativeCodec.encodeImports(
                listOf(rawImport(name = "x".repeat(16 * 1024 * 1024 + 1))),
            )
        }

        assertTrue(failure.message.orEmpty().contains("16777216"), failure.message.orEmpty())
    }

    @Test
    fun rejectsASignatureBeyondTheBridgeCountLimit() {
        val failure = assertFailsWith<IllegalArgumentException> {
            CoreWasmNativeCodec.encodeImports(
                listOf(
                    rawImport(
                        signature = RawFunctionSignature(
                            parameters = List(1_000_001) { RawValueType.I32 },
                        ),
                    ),
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("1000000"), failure.message.orEmpty())
    }

    @Test
    fun encodesAValidImport() {
        val bytes = CoreWasmNativeCodec.encodeImports(
            listOf(rawImport(signature = RawFunctionSignature(listOf(RawValueType.I32), listOf(RawValueType.I64)))),
        )

        assertTrue(bytes.isNotEmpty())
    }

    private fun rawImport(
        name: String = "imported",
        signature: RawFunctionSignature = RawFunctionSignature(),
    ): RawImport = RawImport(
        module = "env",
        name = name,
        signature = signature,
    ) { _, _ -> WasmlineCallResult.Success(emptyList<RawValue>()) }
}
