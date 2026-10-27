package crow.wasmline.plugin.core.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WitBindgenLanguageTest {
    @Test
    fun exposesOneGeneratorTokenPerLanguage() {
        assertEquals(
            listOf("kotlin", "rust", "c", "cpp", "go", "csharp", "moonbit", "markdown"),
            WitBindgenLanguage.tokens,
        )
        assertEquals(WitBindgenLanguage.entries.size, WitBindgenLanguage.tokens.distinct().size)
    }

    @Test
    fun keepsGeneratedSourceExtensionsSeparatePerLanguage() {
        assertEquals(setOf("kt"), WitBindgenLanguage.KOTLIN.extensions)
        assertEquals(setOf("c", "h"), WitBindgenLanguage.C.extensions)
        assertEquals(setOf("cpp", "h"), WitBindgenLanguage.CPP.extensions)
        assertEquals(setOf("cs"), WitBindgenLanguage.CSHARP.extensions)
        assertTrue("h" !in WitBindgenLanguage.GO.extensions)
    }

    @Test
    fun limitsCleanupToFilesTheGeneratorOwns() {
        assertTrue(WitBindgenLanguage.C.ownsFile("plugin_component_type.o"))
        assertTrue(WitBindgenLanguage.C.ownsFile("plugin.h"))
        assertTrue(WitBindgenLanguage.C.ownsFile("plugin.H"))
        assertTrue(!WitBindgenLanguage.C.ownsFile("user-notes.o"))
        assertTrue(!WitBindgenLanguage.C.ownsFile("Plugin.kt"))
        assertTrue(WitBindgenLanguage.KOTLIN.ownsFile("Plugin.kt"))
        assertTrue(!WitBindgenLanguage.KOTLIN.ownsFile("Plugin.rs"))
        assertTrue(!WitBindgenLanguage.GO.ownsFile("plugin.h"))
    }

    @Test
    fun treatsEveryProducedExtensionAsOwned() {
        WitBindgenLanguage.entries.forEach { language ->
            language.extensions.forEach { extension ->
                assertTrue(language.ownsFile("plugin.$extension"), "${language.generator}: $extension")
            }
        }
    }

    @Test
    fun declaresOnlyRequiredGeneratorFlags() {
        assertEquals(listOf("--runtime", "native-aot"), WitBindgenLanguage.CSHARP.requiredArguments)
        WitBindgenLanguage.entries
            .filter { it != WitBindgenLanguage.CSHARP }
            .forEach { language -> assertEquals(emptyList(), language.requiredArguments, language.generator) }
    }
}
