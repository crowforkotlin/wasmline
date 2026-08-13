package crow.wasmline.plugin.core.component.hostgen

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class WitParserTest {
    @Test
    fun parsesInlineWorldInterfaces() {
        val root = createTempDirectory("wasmline-inline-wit").toFile()
        try {
            val source = File(root, "world.wit").apply {
                writeText(
                    """
                    package wasmline:service@1.0.0;

                    world plugin {
                      import host: interface {
                        invoke: func(payload: list<u8>) -> list<u8>;
                      }
                      export plugin: interface {
                        invoke: func(payload: list<u8>) -> list<u8>;
                      }
                    }
                    """.trimIndent(),
                )
            }

            val parsed = WitParser.parse(source)

            assertEquals(listOf("host"), parsed.worlds.getValue("plugin").imports)
            assertEquals(listOf("plugin"), parsed.worlds.getValue("plugin").exports)
            assertEquals("invoke", parsed.interfaces.getValue("host").functions.single().name)
            assertEquals("invoke", parsed.interfaces.getValue("plugin").functions.single().name)
        } finally {
            root.deleteRecursively()
        }
    }
}
