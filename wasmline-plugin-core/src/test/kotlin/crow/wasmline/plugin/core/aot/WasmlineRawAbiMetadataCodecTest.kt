package crow.wasmline.plugin.core.aot

import crow.wasmline.CoreWasmFeature
import crow.wasmline.RawAbiMetadata
import crow.wasmline.RawExport
import crow.wasmline.RawExportKind
import crow.wasmline.RawFunctionSignature
import crow.wasmline.RawImportDeclaration
import crow.wasmline.RawValueType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies canonical raw ABI metadata shared by Gradle and CLI adapters.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
class WasmlineRawAbiMetadataCodecTest {
    @Test
    fun roundTripsCanonicalMetadata() {
        val metadata = RawAbiMetadata(
            exports = listOf(
                RawExport("z", RawExportKind.GLOBAL),
                RawExport(
                    "add_i32",
                    RawExportKind.FUNCTION,
                    RawFunctionSignature(listOf(RawValueType.I32, RawValueType.I32), listOf(RawValueType.I32)),
                ),
            ),
            imports = listOf(
                RawImportDeclaration("time", "now", RawFunctionSignature(results = listOf(RawValueType.I64))),
                RawImportDeclaration("audio", "submit", RawFunctionSignature(parameters = listOf(RawValueType.I32))),
            ),
            requiredFeatures = setOf(CoreWasmFeature.SIMD, CoreWasmFeature.I64),
        )

        val encoded = WasmlineRawAbiMetadataCodec.encode(metadata)
        val decoded = WasmlineRawAbiMetadataCodec.decode(encoded)

        assertEquals(listOf("add_i32", "z"), decoded.exports.map(RawExport::name))
        assertEquals(listOf("audio.submit", "time.now"), decoded.imports.map { "${it.module}.${it.name}" })
        assertEquals(listOf(CoreWasmFeature.I64, CoreWasmFeature.SIMD), decoded.requiredFeatures.toList())
        assertEquals(encoded, WasmlineRawAbiMetadataCodec.encode(decoded))
    }

    @Test
    fun rejectsSignaturesOnNonFunctionExports() {
        val metadata = RawAbiMetadata(
            exports = listOf(
                RawExport("memory", RawExportKind.MEMORY, RawFunctionSignature()),
            ),
        )

        assertFailsWith<IllegalArgumentException> { WasmlineRawAbiMetadataCodec.encode(metadata) }
    }
}
