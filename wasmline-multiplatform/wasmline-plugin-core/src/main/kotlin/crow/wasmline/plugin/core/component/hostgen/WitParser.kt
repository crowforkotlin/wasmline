package crow.wasmline.plugin.core.component.hostgen

import java.io.File

object WitParser {
    fun parse(path: File): WitPackage {
        val sources = WitSources.load(path)
        return Parser(Lexer(sources.source).tokens(), sources.sha256).parsePackage()
    }
}

private enum class TokenKind { IDENTIFIER, SYMBOL, EOF }

private data class Token(val kind: TokenKind, val text: String, val offset: Int)

private class Lexer(private val source: String) {
    private var offset = 0

    fun tokens(): List<Token> = buildList {
        while (true) {
            skipTrivia()
            if (offset >= source.length) {
                add(Token(TokenKind.EOF, "", offset))
                return@buildList
            }
            val start = offset
            val current = source[offset]
            if (current.isLetterOrDigit() || current == '_' || current == '-') {
                offset++
                while (offset < source.length) {
                    val char = source[offset]
                    if (!(char.isLetterOrDigit() || char == '_' || char == '-')) break
                    offset++
                }
                add(Token(TokenKind.IDENTIFIER, source.substring(start, offset), start))
            } else {
                offset++
                add(Token(TokenKind.SYMBOL, current.toString(), start))
            }
        }
    }

    private fun skipTrivia() {
        while (offset < source.length) {
            when {
                source[offset].isWhitespace() -> offset++

                source.startsWith("///", offset) || source.startsWith("//", offset) -> {
                    offset = source.indexOf('\n', offset).takeIf { it >= 0 } ?: source.length
                }

                source.startsWith("/*", offset) -> {
                    val end = source.indexOf("*/", offset + 2)
                    if (end < 0) throw WitParseException("Unterminated WIT block comment at offset $offset.")
                    offset = end + 2
                }

                else -> return
            }
        }
    }
}

private class Parser(private val tokens: List<Token>, private val sha256: String) {
    private var index = 0

    fun parsePackage(): WitPackage {
        val packageId = parsePackageId()
        expect(";")
        val interfaces = linkedMapOf<String, WitInterface>()
        val worlds = linkedMapOf<String, WitWorld>()
        while (!atEnd()) {
            when (peek().text) {
                "package" -> {
                    val additionalPackage = parsePackageId()
                    expect(";")
                    if (additionalPackage != packageId) {
                        fail("Root WIT files declare different packages '$packageId' and '$additionalPackage'.")
                    }
                }

                "interface" -> parseInterface().also { requireUnique(interfaces, it.name, "interface", it) }

                "world" -> parseWorld(interfaces).also { requireUnique(worlds, it.name, "world", it) }

                else -> fail("Expected package, interface or world, found '${peek().text}'.")
            }
        }
        if (worlds.isEmpty()) fail("WIT package '$packageId' contains no world.")
        return WitPackage(packageId, interfaces, worlds, sha256)
    }

    private fun parsePackageId(): String {
        expect("package")
        return buildString {
            append(identifier("package namespace"))
            expect(":")
            append(':')
            append(identifier("package name"))
            if (consume("@")) {
                append('@')
                append(identifier("package version"))
                while (consume(".")) {
                    append('.')
                    append(identifier("package version"))
                }
            }
        }
    }

    private fun parseInterface(): WitInterface {
        expect("interface")
        val name = identifier("interface name")
        return parseInterfaceBody(name)
    }

    private fun parseInterfaceBody(name: String): WitInterface {
        expect("{")
        val types = mutableListOf<WitTypeDefinition>()
        val functions = mutableListOf<WitFunction>()
        val uses = mutableListOf<WitUse>()
        while (!consume("}")) {
            when (peek().text) {
                "record" -> types += parseRecord()
                "enum" -> types += parseEnum()
                "flags" -> types += parseFlags()
                "variant" -> types += parseVariant()
                "resource" -> types += parseResource()
                "type" -> types += parseAlias()
                "use" -> uses += parseUse()
                else -> functions += parseFunction(WitFunction.Kind.FUNCTION)
            }
        }
        return WitInterface(name, types, functions, uses)
    }

    private fun parseRecord(): WitTypeDefinition.Record {
        expect("record")
        val name = identifier("record name")
        expect("{")
        val fields = mutableListOf<WitField>()
        while (!consume("}")) {
            fields += WitField(identifier("record field"), expectTypeAfterColon())
            consume(",")
        }
        return WitTypeDefinition.Record(name, fields)
    }

    private fun parseEnum(): WitTypeDefinition.Enum {
        expect("enum")
        val name = identifier("enum name")
        return WitTypeDefinition.Enum(name, parseNameBlock("enum case"))
    }

    private fun parseFlags(): WitTypeDefinition.Flags {
        expect("flags")
        val name = identifier("flags name")
        return WitTypeDefinition.Flags(name, parseNameBlock("flag"))
    }

    private fun parseNameBlock(label: String): List<String> {
        expect("{")
        val names = mutableListOf<String>()
        while (!consume("}")) {
            names += identifier(label)
            consume(",")
        }
        return names
    }

    private fun parseVariant(): WitTypeDefinition.Variant {
        expect("variant")
        val name = identifier("variant name")
        expect("{")
        val cases = mutableListOf<WitCase>()
        while (!consume("}")) {
            val caseName = identifier("variant case")
            val type = if (consume("(")) parseType().also { expect(")") } else null
            cases += WitCase(caseName, type)
            consume(",")
        }
        return WitTypeDefinition.Variant(name, cases)
    }

    private fun parseAlias(): WitTypeDefinition.Alias {
        expect("type")
        val name = identifier("type alias")
        expect("=")
        val type = parseType()
        expect(";")
        return WitTypeDefinition.Alias(name, type)
    }

    private fun parseUse(): WitUse {
        expect("use")
        val interfaceName = identifier("use interface")
        expect(".")
        expect("{")
        val names = mutableListOf<String>()
        while (!consume("}")) {
            names += identifier("used type")
            consume(",")
        }
        expect(";")
        return WitUse(interfaceName, names)
    }

    private fun parseResource(): WitTypeDefinition.Resource {
        expect("resource")
        val name = identifier("resource name")
        expect("{")
        var constructor: WitFunction? = null
        val methods = mutableListOf<WitFunction>()
        while (!consume("}")) {
            if (consume("constructor")) {
                check(constructor == null) { "Resource '$name' declares more than one constructor." }
                constructor = parseFunctionTail("constructor", WitFunction.Kind.CONSTRUCTOR, hasFuncKeyword = false)
            } else {
                methods += parseFunction(WitFunction.Kind.METHOD)
            }
        }
        return WitTypeDefinition.Resource(name, constructor, methods)
    }

    private fun parseFunction(kind: WitFunction.Kind): WitFunction {
        val name = identifier("function name")
        expect(":")
        return parseFunctionTail(name, kind)
    }

    private fun parseFunctionTail(name: String, kind: WitFunction.Kind, hasFuncKeyword: Boolean = true): WitFunction {
        if (hasFuncKeyword) expect("func")
        expect("(")
        val parameters = mutableListOf<WitField>()
        while (!consume(")")) {
            parameters += WitField(identifier("function parameter"), expectTypeAfterColon())
            if (!consume(",")) expect(")").also { index-- }
        }
        val result = if (consume("-")) {
            expect(">")
            parseType()
        } else {
            null
        }
        expect(";")
        return WitFunction(name, parameters, result, kind)
    }

    private fun expectTypeAfterColon(): WitType {
        expect(":")
        return parseType()
    }

    private fun parseType(): WitType {
        val token = peek()
        val name = identifier("type")
        return when (name) {
            "bool", "s8", "u8", "s16", "u16", "s32", "u32", "s64", "u64", "f32", "f64", "char", "string" ->
                WitType.Primitive(name)

            "list" -> WitType.ListType(parseSingleTypeArgument())

            "option" -> WitType.Option(parseSingleTypeArgument())

            "own" -> WitType.Own(parseNamedTypeArgument())

            "borrow" -> WitType.Borrow(parseNamedTypeArgument())

            "future", "stream", "pollable", "error-context" ->
                throw UnsupportedWitFeatureException(
                    "WIT async type '$name' is not supported by the Wasmline Host binding runtime " +
                        "because async Component Store and coroutine facade support are not enabled (offset ${token.offset}).",
                )

            "tuple" -> {
                expect("<")
                val values = mutableListOf<WitType>()
                while (!consume(">")) {
                    values += parseType()
                    if (!consume(",")) expect(">").also { index-- }
                }
                WitType.Tuple(values)
            }

            "result" -> {
                expect("<")
                val ok = if (consume("_")) null else parseType()
                val error = if (consume(",")) if (consume("_")) null else parseType() else null
                expect(">")
                WitType.Result(ok, error)
            }

            "_" -> WitType.UnitType

            else -> WitType.Named(name)
        }
    }

    private fun parseSingleTypeArgument(): WitType {
        expect("<")
        val type = parseType()
        expect(">")
        return type
    }

    private fun parseNamedTypeArgument(): String {
        expect("<")
        val type = identifier("resource type")
        expect(">")
        return type
    }

    private fun parseWorld(interfaces: MutableMap<String, WitInterface>): WitWorld {
        expect("world")
        val name = identifier("world name")
        expect("{")
        val imports = mutableListOf<String>()
        val exports = mutableListOf<String>()
        while (!consume("}")) {
            when {
                consume("import") -> imports += parseWorldInterface("world import", interfaces)
                consume("export") -> exports += parseWorldInterface("world export", interfaces)
                else -> fail("Expected import or export in world '$name'.")
            }
        }
        return WitWorld(name, imports, exports)
    }

    private fun parseWorldInterface(label: String, interfaces: MutableMap<String, WitInterface>): String {
        val name = identifier(label)
        if (consume(":")) {
            expect("interface")
            val inlineInterface = parseInterfaceBody(name)
            requireUnique(interfaces, name, "interface", inlineInterface)
            consume(";")
        } else {
            expect(";")
        }
        return name
    }

    private fun expect(text: String) {
        if (!consume(text)) fail("Expected '$text', found '${peek().text}'.")
    }

    private fun consume(text: String): Boolean {
        if (peek().text != text) return false
        index++
        return true
    }

    private fun identifier(label: String): String {
        val token = peek()
        if (token.kind != TokenKind.IDENTIFIER) fail("Expected $label, found '${token.text}'.")
        index++
        return token.text
    }

    private fun peek(): Token = tokens[index]

    private fun atEnd(): Boolean = peek().kind == TokenKind.EOF

    private fun fail(message: String): Nothing = throw WitParseException("$message (offset ${peek().offset})")

    private fun <T> requireUnique(destination: MutableMap<String, T>, name: String, label: String, value: T) {
        if (destination.put(name, value) != null) fail("Duplicate WIT $label '$name'.")
    }
}
