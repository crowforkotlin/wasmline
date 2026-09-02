@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Defines scalar Core WebAssembly value types supported by `RAW_EXPORT`.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
@Serializable
enum class RawValueType {
    I32,
    I64,
    F32,
    F64,
}

/**
 * Represents one scalar value crossing a Core WebAssembly call boundary.
 *
 * `i64` is represented by Kotlin [Long] and is converted to JavaScript
 * `BigInt` by Web backends. Floating-point values preserve IEEE-754 value
 * semantics, including infinities and signed zero.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
sealed interface RawValue {
    /**
     * Core WebAssembly `i32` value.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     *
     * @property value Signed two's-complement payload.
     */
    data class I32(val value: Int) : RawValue

    /**
     * Core WebAssembly `i64` value.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     *
     * @property value Signed two's-complement payload.
     */
    data class I64(val value: Long) : RawValue

    /**
     * Core WebAssembly `f32` value.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     *
     * @property value IEEE-754 binary32 value.
     */
    data class F32(val value: Float) : RawValue

    /**
     * Core WebAssembly `f64` value.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     *
     * @property value IEEE-754 binary64 value.
     */
    data class F64(val value: Double) : RawValue

    /** Returns the declared Core WebAssembly type of this value. */
    val type: RawValueType
        get() = when (this) {
            is I32 -> RawValueType.I32
            is I64 -> RawValueType.I64
            is F32 -> RawValueType.F32
            is F64 -> RawValueType.F64
        }
}

/**
 * Describes the scalar parameter and result types of a Core Wasm function.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 *
 * @property parameters Parameter types in declaration order.
 * @property results Result types in declaration order.
 */
@Serializable
data class RawFunctionSignature(
    @property:ProtoNumber(1) val parameters: List<RawValueType> = emptyList(),
    @property:ProtoNumber(2) val results: List<RawValueType> = emptyList(),
)

/**
 * Identifies the kind of an exported Core WebAssembly item.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
@Serializable
enum class RawExportKind {
    FUNCTION,
    MEMORY,
    GLOBAL,
    TABLE,
    UNKNOWN,
}

/**
 * Describes an exported item visible from a Core WebAssembly module.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 *
 * @property name Exact export name.
 * @property kind Exported item kind.
 * @property signature Function signature when known from module reflection or ABI metadata.
 */
@Serializable
data class RawExport(
    @property:ProtoNumber(1) val name: String,
    @property:ProtoNumber(2) val kind: RawExportKind,
    @property:ProtoNumber(3) val signature: RawFunctionSignature? = null,
)

/**
 * Describes one imported Core WebAssembly host function in ABI metadata.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 *
 * @property module Import module namespace.
 * @property name Import field name.
 * @property signature Function signature.
 */
@Serializable
data class RawImportDeclaration(
    @property:ProtoNumber(1) val module: String,
    @property:ProtoNumber(2) val name: String,
    @property:ProtoNumber(3) val signature: RawFunctionSignature,
)

/**
 * Identifies an optional Core WebAssembly feature relevant to artifact selection.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 */
@Serializable
enum class CoreWasmFeature {
    MULTI_VALUE,
    I64,
    SIMD,
    THREADS,
    BULK_MEMORY,
    REFERENCE_TYPES,
}

/**
 * Versioned ABI metadata used when a backend cannot reflect function signatures.
 *
 * Date: 2026-08-25
 * Author: crowforkotlin
 *
 * @property version Metadata schema version. Version `1` is the current schema.
 * @property exports Declared module exports and optional function signatures.
 * @property imports Declared host function imports.
 * @property memoryExport Name of the primary exported linear memory, or `null` when absent.
 * @property requiredFeatures Features required before the module can be instantiated.
 */
@Serializable
data class RawAbiMetadata(
    @property:ProtoNumber(1) val version: Int = CURRENT_VERSION,
    @property:ProtoNumber(2) val exports: List<RawExport> = emptyList(),
    @property:ProtoNumber(3) val imports: List<RawImportDeclaration> = emptyList(),
    @property:ProtoNumber(4) val memoryExport: String? = DEFAULT_MEMORY_EXPORT,
    @property:ProtoNumber(5) val requiredFeatures: Set<CoreWasmFeature> = emptySet(),
) {
    init {
        require(version > 0) { "Raw ABI metadata version must be positive." }
        require(exports.map(RawExport::name).none(String::isBlank)) { "Raw ABI export names must not be blank." }
        require(imports.none { it.module.isBlank() || it.name.isBlank() }) { "Raw ABI import names must not be blank." }
        require(exports.map(RawExport::name).distinct().size == exports.size) {
            "Raw ABI export names must be unique."
        }
        require(imports.map { it.module to it.name }.distinct().size == imports.size) {
            "Raw ABI import names must be unique."
        }
        require(memoryExport == null || memoryExport.isNotBlank()) { "Raw ABI memory export name must not be blank." }
    }

    /**
     * Defines constants for the versioned raw ABI metadata schema.
     *
     * Date: 2026-08-25
     * Author: crowforkotlin
     */
    companion object {
        /** Current raw ABI metadata schema version. */
        const val CURRENT_VERSION: Int = 1

        /** Conventional exported linear-memory name. */
        const val DEFAULT_MEMORY_EXPORT: String = "memory"
    }
}
