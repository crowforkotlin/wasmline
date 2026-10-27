package crow.wasmline.plugin.core.component

import crow.wasmline.plugin.core.InternalWasmlineToolingApi

/**
 * One `wit-bindgen` guest generator that Wasmline can invoke.
 *
 * [extensions] lists the source files that prove generation worked, and bounds
 * the cleanup that runs before it. [staleSuffixes] adds generated artifacts
 * without a distinguishing extension, namely `wit-bindgen`'s own
 * `*_component_type.o`; plain `.o` files from a shared build directory are left
 * alone. [requiredArguments] holds flags without which the pinned generator
 * refuses to run.
 */
@InternalWasmlineToolingApi
enum class WitBindgenLanguage(
    val displayName: String,
    val generator: String,
    val extensions: Set<String>,
    val requiredArguments: List<String> = emptyList(),
    val staleSuffixes: Set<String> = emptySet(),
) {
    KOTLIN("Kotlin", "kotlin", setOf("kt")),
    RUST("Rust", "rust", setOf("rs")),
    C("C", "c", setOf("c", "h"), staleSuffixes = setOf("_component_type.o")),
    CPP("C++", "cpp", setOf("cpp", "h"), staleSuffixes = setOf("_component_type.o")),
    GO("Go", "go", setOf("go")),
    CSHARP("C#", "csharp", setOf("cs"), listOf("--runtime", "native-aot")),
    MOONBIT("MoonBit", "moonbit", setOf("mbt")),
    MARKDOWN("Markdown", "markdown", setOf("md")),
    ;

    /** Whether [fileName] is an output this generator owns and may replace. */
    fun ownsFile(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return extension in extensions || staleSuffixes.any { suffix -> fileName.endsWith(suffix) }
    }

    companion object {
        /** Lowercase tokens accepted by `--lang`, in declaration order. */
        val tokens: List<String> = entries.map { it.generator }
    }
}
