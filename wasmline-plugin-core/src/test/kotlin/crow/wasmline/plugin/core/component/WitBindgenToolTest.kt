package crow.wasmline.plugin.core.component

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WitBindgenToolTest {
    @Test
    fun passesTheSelectedGeneratorAndWritesItsExtension() = withToolDirectory { root ->
        val wit = witDirectory(root)
        val output = File(root, "generated")
        val log = File(root, "invocations.txt")

        WitBindgenTool(stubGenerator(root, log, listOf("plugin.c", "plugin.h"))).generate(
            WitBindingsRequest(
                witDirectory = wit,
                outputDirectory = output,
                language = WitBindgenLanguage.C,
                world = "plugin",
            ),
        )

        val invocation = log.readText().trim().lines().last()
        assertTrue(invocation.startsWith("c "), invocation)
        assertTrue(invocation.contains("--world plugin"), invocation)
        assertTrue(invocation.contains("--out-dir=" + output.absolutePath), invocation)
        assertTrue(invocation.contains(wit.absolutePath), invocation)
        assertTrue(File(output, "plugin.c").isFile)
    }

    @Test
    fun removesOnlyTheSelectedLanguageOutput() = withToolDirectory { root ->
        val wit = witDirectory(root)
        val output = File(root, "generated").apply {
            mkdirs()
            File(this, "Stale.kt").writeText("stale")
            File(this, "Stale.c").writeText("stale")
        }

        WitBindgenTool(stubGenerator(root, File(root, "invocations.txt"), listOf("plugin.c"))).generate(
            WitBindingsRequest(
                witDirectory = wit,
                outputDirectory = output,
                language = WitBindgenLanguage.C,
                world = "plugin",
            ),
        )

        assertTrue(!File(output, "Stale.c").exists())
        assertTrue(File(output, "Stale.kt").isFile)
    }

    @Test
    fun failsWhenTheGeneratorWritesNoMatchingFile() = withToolDirectory { root ->
        val wit = witDirectory(root)

        val failure = assertFailsWith<IllegalStateException> {
            WitBindgenTool(stubGenerator(root, File(root, "invocations.txt"), listOf("plugin.kt"))).generate(
                WitBindingsRequest(
                    witDirectory = wit,
                    outputDirectory = File(root, "generated"),
                    language = WitBindgenLanguage.C,
                    world = "plugin",
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("without producing C source"), failure.message.orEmpty())
    }

    @Test
    fun rejectsKotlinImportsForOtherGenerators() = withToolDirectory { root ->
        val wit = witDirectory(root)

        val failure = assertFailsWith<IllegalArgumentException> {
            WitBindgenTool(stubGenerator(root, File(root, "invocations.txt"), listOf("plugin.rs"))).generate(
                WitBindingsRequest(
                    witDirectory = wit,
                    outputDirectory = File(root, "generated"),
                    language = WitBindgenLanguage.RUST,
                    world = "plugin",
                    kotlinImports = "impl.*",
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("--kotlin-imports"), failure.message.orEmpty())
    }

    @Test
    fun passesRequiredGeneratorFlags() = withToolDirectory { root ->
        val wit = witDirectory(root)
        val log = File(root, "invocations.txt")

        WitBindgenTool(stubGenerator(root, log, listOf("Plugin.cs"))).generate(
            WitBindingsRequest(
                witDirectory = wit,
                outputDirectory = File(root, "generated"),
                language = WitBindgenLanguage.CSHARP,
                world = "plugin",
            ),
        )

        val invocation = log.readText().trim().lines().last()
        assertTrue(invocation.startsWith("csharp "), invocation)
        assertTrue(invocation.contains("--runtime native-aot"), invocation)
    }

    @Test
    fun forwardsKotlinImportsToTheKotlinGenerator() = withToolDirectory { root ->
        val wit = witDirectory(root)
        val log = File(root, "invocations.txt")

        WitBindgenTool(stubGenerator(root, log, listOf("Plugin.kt"))).generate(
            WitBindingsRequest(
                witDirectory = wit,
                outputDirectory = File(root, "generated"),
                language = WitBindgenLanguage.KOTLIN,
                world = "plugin",
                kotlinImports = "impl.*",
            ),
        )

        val invocation = log.readText().trim().lines().last()
        assertTrue(invocation.startsWith("kotlin "), invocation)
        assertTrue(invocation.contains("--kotlin-imports impl.*"), invocation)
    }

    private fun witDirectory(root: File): File = File(root, "wit").apply {
        mkdirs()
        File(this, "wasmline.wit").writeText("package wasmline:service@1.0.0; world plugin {}\n")
    }

    /**
     * Writes a POSIX shell stand-in that records each call and creates the listed
     * files inside `--out-dir`, so the test never needs the real toolchain.
     */
    private fun stubGenerator(root: File, log: File, producedFiles: List<String>): File {
        val creates = producedFiles.joinToString("\n") { name ->
            "printf x > \"\$out_dir/$name\""
        }
        val script = File(root, "wit-bindgen-stub.sh")
        script.writeText(
            listOf(
                "#!/bin/sh",
                "echo \"\$*\" >> \"${log.absolutePath}\"",
                "if [ \"\$1\" = \"--version\" ]; then echo \"wit-bindgen-cli 0.57.1\"; exit 0; fi",
                "if [ \"\$2\" = \"--help\" ]; then exit 0; fi",
                "out_dir=\"\"",
                "for argument in \"\$@\"; do",
                "  case \"\$argument\" in",
                "    --out-dir=*) out_dir=\"\${argument#--out-dir=}\" ;;",
                "  esac",
                "done",
                "[ -n \"\$out_dir\" ] || exit 3",
                "mkdir -p \"\$out_dir\"",
                creates,
                "exit 0",
                "",
            ).joinToString("\n"),
        )
        assertTrue(script.setExecutable(true) || script.canExecute())
        return script
    }
}

private inline fun withToolDirectory(block: (File) -> Unit) {
    val directory = createTempDirectory("wasmline-wit-bindgen-test").toFile()
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}
