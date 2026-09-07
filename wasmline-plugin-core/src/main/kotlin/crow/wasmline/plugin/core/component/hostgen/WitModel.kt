package crow.wasmline.plugin.core.component.hostgen

internal data class WitPackage(
    val packageId: String,
    val interfaces: Map<String, WitInterface>,
    val worlds: Map<String, WitWorld>,
    val sha256: String,
)

internal data class WitInterface(
    val name: String,
    val types: List<WitTypeDefinition>,
    val functions: List<WitFunction>,
    val uses: List<WitUse>,
)

internal data class WitWorld(val name: String, val imports: List<String>, val exports: List<String>)

internal data class WitUse(val interfaceName: String, val names: List<String>)

internal sealed interface WitTypeDefinition {
    val name: String

    data class Record(override val name: String, val fields: List<WitField>) : WitTypeDefinition
    data class Enum(override val name: String, val cases: List<String>) : WitTypeDefinition
    data class Flags(override val name: String, val flags: List<String>) : WitTypeDefinition
    data class Variant(override val name: String, val cases: List<WitCase>) : WitTypeDefinition
    data class Alias(override val name: String, val type: WitType) : WitTypeDefinition
    data class Resource(override val name: String, val constructor: WitFunction?, val methods: List<WitFunction>) : WitTypeDefinition
}

internal data class WitField(val name: String, val type: WitType)

internal data class WitCase(val name: String, val type: WitType?)

internal data class WitFunction(val name: String, val parameters: List<WitField>, val result: WitType?, val kind: Kind = Kind.FUNCTION) {
    enum class Kind {
        FUNCTION,
        CONSTRUCTOR,
        METHOD,
    }
}

internal sealed interface WitType {
    data class Primitive(val name: String) : WitType
    data class Named(val name: String) : WitType
    data class ListType(val element: WitType) : WitType
    data class Option(val value: WitType) : WitType
    data class Tuple(val values: List<WitType>) : WitType
    data class Result(val ok: WitType?, val error: WitType?) : WitType
    data class Own(val resource: String) : WitType
    data class Borrow(val resource: String) : WitType
    data object UnitType : WitType
}

internal class WitParseException(message: String) : IllegalArgumentException(message)

internal class UnsupportedWitFeatureException(message: String) : IllegalArgumentException(message)
