package crow.wasmline

/**
 * Defines values for direct Component Model export calls.
 *
 * Date: 2026-08-02
 * Author: crowforkotlin
 */
sealed interface WasmlineComponentValue {
    data class Bool(val value: Boolean) : WasmlineComponentValue

    data class S8(val value: Byte) : WasmlineComponentValue

    data class U8(val value: UByte) : WasmlineComponentValue

    data class S16(val value: Short) : WasmlineComponentValue

    data class U16(val value: UShort) : WasmlineComponentValue

    data class S32(val value: Int) : WasmlineComponentValue

    data class U32(val value: UInt) : WasmlineComponentValue

    data class S64(val value: Long) : WasmlineComponentValue

    data class U64(val value: ULong) : WasmlineComponentValue

    data class F32(val value: Float) : WasmlineComponentValue

    data class F64(val value: Double) : WasmlineComponentValue

    data class CharValue(val value: UInt) : WasmlineComponentValue

    data class StringValue(val value: String) : WasmlineComponentValue

    data class ListValue(val values: List<WasmlineComponentValue>) : WasmlineComponentValue

    data class RecordValue(val fields: List<RecordField>) : WasmlineComponentValue

    data class TupleValue(val values: List<WasmlineComponentValue>) : WasmlineComponentValue

    data class VariantValue(val discriminant: String, val value: WasmlineComponentValue? = null) : WasmlineComponentValue

    data class EnumValue(val name: String) : WasmlineComponentValue

    data class OptionValue(val value: WasmlineComponentValue? = null) : WasmlineComponentValue

    data class ResultValue(val isOk: Boolean, val value: WasmlineComponentValue? = null) : WasmlineComponentValue

    data class FlagsValue(val names: List<String>) : WasmlineComponentValue

    data class MapValue(val entries: List<MapEntry>) : WasmlineComponentValue

    /** Session-scoped carrier for a validated Component Model resource. */
    class ResourceValue internal constructor(
        val instanceKey: String,
        val typeId: UInt,
        val handleId: ULong,
        val generation: UInt,
        val ownership: WasmlineComponentResourceOwnership,
        val origin: WasmlineComponentResourceOrigin,
    ) : WasmlineComponentValue {
        init {
            require(instanceKey.isNotBlank()) { "Component resource instance key must not be blank." }
            require(typeId != 0u) { "Component resource type id must not be zero." }
            require(handleId != 0uL) { "Component resource handle id must not be zero." }
            require(generation != 0u) { "Component resource generation must not be zero." }
        }

        override fun equals(other: Any?): Boolean = other is ResourceValue &&
            instanceKey == other.instanceKey &&
            typeId == other.typeId &&
            handleId == other.handleId &&
            generation == other.generation &&
            ownership == other.ownership &&
            origin == other.origin

        override fun hashCode(): Int {
            var result = instanceKey.hashCode()
            result = 31 * result + typeId.hashCode()
            result = 31 * result + handleId.hashCode()
            result = 31 * result + generation.hashCode()
            result = 31 * result + ownership.hashCode()
            return 31 * result + origin.hashCode()
        }

        override fun toString(): String =
            "ResourceValue(instanceKey=$instanceKey, typeId=$typeId, handleId=$handleId, generation=$generation, ownership=$ownership, origin=$origin)"
    }

    data class RecordField(val name: String, val value: WasmlineComponentValue)

    data class MapEntry(val key: WasmlineComponentValue, val value: WasmlineComponentValue)
}

enum class WasmlineComponentResourceOwnership { OWN, BORROW }

enum class WasmlineComponentResourceOrigin { GUEST, HOST }
