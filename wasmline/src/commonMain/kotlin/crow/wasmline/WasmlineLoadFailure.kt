package crow.wasmline

import crow.wasmline.invocation.WasmlineErrorCode

/**
 * Identifies the stage at which a Wasmline load operation failed.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
enum class WasmlineLoadStage {
    SOURCE_RESOLUTION,
    MANIFEST_DECODING,
    SIGNATURE_VERIFICATION,
    ARTIFACT_SELECTION,
    ARTIFACT_RESOLUTION,
    ARTIFACT_VALIDATION,
    MODULE_CREATION,
}

/**
 * Describes a structured failure produced before a module is available.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 *
 * @property stage Load pipeline stage that failed.
 * @property code Stable failure category.
 * @property message Human-readable diagnostic message.
 * @property details Optional backend-specific diagnostic bytes.
 * @property rawCode Original numeric error code received across a bridge.
 */
data class WasmlineLoadFailure(
    val stage: WasmlineLoadStage,
    val code: WasmlineErrorCode,
    val message: String,
    val details: ByteArray? = null,
    val rawCode: Int = code.value,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WasmlineLoadFailure) return false
        return stage == other.stage &&
            code == other.code &&
            message == other.message &&
            rawCode == other.rawCode &&
            details.contentEquals(other.details)
    }

    override fun hashCode(): Int {
        var result = stage.hashCode()
        result = 31 * result + code.hashCode()
        result = 31 * result + message.hashCode()
        result = 31 * result + rawCode
        result = 31 * result + (details?.contentHashCode() ?: 0)
        return result
    }
}
