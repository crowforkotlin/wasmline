@file:Suppress("SameParameterValue")

package crow.wasmline.cli

import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption

public fun main(vararg args: String) {
    printHeader("wasmline command line")

    val tools = NoOpCliktCommand(name = "tools")
        .subcommands(commands = arrayOf(ToolDownload()))
    val wit = NoOpCliktCommand(name = "wit")
        .subcommands(commands = arrayOf(WitGenerate()))
    val component = NoOpCliktCommand(name = "component")
        .subcommands(commands = arrayOf(ComponentValidate(), ComponentInspect()))
    val aotProfiles = NoOpCliktCommand(name = "aot-profiles")
        .subcommands(commands = arrayOf(AotProfilesList(), AotProfilesDescribe()))

    NoOpCliktCommand(name = "wasmline")
        .subcommands(
            commands = arrayOf(
                Build(),
                Compile(),
                Manifest(),
                GenerateKeyPair(),
                Componentize(),
                aotProfiles,
                tools,
                wit,
                component,
            ),
        )
        .versionOption(version = BuildConfig.VERSION)
        .main(argv = args)
}

private const val CYAN = "\u001B[36m"
private const val NC = "\u001B[0m"
private const val LINE = "================================================="
private const val COLORED_BORDER = "$CYAN$LINE$NC\n"

private fun printHeader(message: String) {
    val output = buildString(message.length + 128) {
        append(COLORED_BORDER)
        append(CYAN).append(message).append("       ").append(NC).append('\n')
        append(COLORED_BORDER)
    }
    print(output)
}
