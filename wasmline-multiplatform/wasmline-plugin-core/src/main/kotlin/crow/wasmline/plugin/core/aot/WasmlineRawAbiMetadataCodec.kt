package crow.wasmline.plugin.core.aot

import crow.wasmline.RawAbiMetadata
import crow.wasmline.loader.model.WasmlineManifestLimits
import crow.wasmline.loader.model.WasmlineManifestProtocol
import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Encodes and validates canonical raw ABI metadata shared by Gradle and CLI build adapters.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
object WasmlineRawAbiMetadataCodec {
    private val json = Json {
        encodeDefaults = true
    }

    /** Encodes canonical raw ABI metadata as deterministic JSON. */
    fun encode(metadata: RawAbiMetadata): String {
        val canonical = validateAndCanonicalize(metadata)
        return json.encodeToString(canonical)
    }

    /** Decodes, validates, and canonicalizes raw ABI metadata JSON. */
    fun decode(encoded: String): RawAbiMetadata {
        require(encoded.isNotBlank()) { "Raw ABI metadata JSON must not be blank." }
        require(encoded.encodeToByteArray().size <= WasmlineManifestLimits.DEFAULT_MAX_PAYLOAD_BYTES) {
            "Raw ABI metadata exceeds the ${WasmlineManifestLimits.DEFAULT_MAX_PAYLOAD_BYTES}-byte limit."
        }
        return validateAndCanonicalize(json.decodeFromString(encoded))
    }

    /** Reads raw ABI metadata from one bounded JSON file. */
    fun read(inputFile: File): RawAbiMetadata {
        require(inputFile.isFile) { "Raw ABI metadata file does not exist: ${inputFile.absolutePath}" }
        require(inputFile.length() <= WasmlineManifestLimits.DEFAULT_MAX_PAYLOAD_BYTES) {
            "Raw ABI metadata file exceeds the ${WasmlineManifestLimits.DEFAULT_MAX_PAYLOAD_BYTES}-byte limit."
        }
        return decode(inputFile.readText())
    }

    private fun validateAndCanonicalize(metadata: RawAbiMetadata): RawAbiMetadata {
        WasmlineManifestProtocol.rawAbiValidationError(metadata)?.let { error ->
            throw IllegalArgumentException("Invalid raw ABI metadata: $error")
        }
        return WasmlineManifestProtocol.canonicalizeRawAbi(metadata)
    }
}
