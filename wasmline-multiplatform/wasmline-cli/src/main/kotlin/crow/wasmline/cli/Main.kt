package crow.wasmline.cli

import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import crow.wasmline.extensions.printHeader

fun main(vararg args: String) {
    printHeader("wasmline command line")

    val tools = NoOpCliktCommand(name = "tools")
        .subcommands(commands = arrayOf(ToolDownload()))
    val wit = NoOpCliktCommand(name = "wit")
        .subcommands(commands = arrayOf(WitGenerate()))
    val component = NoOpCliktCommand(name = "component")
        .subcommands(commands = arrayOf(ComponentValidate(), ComponentInspect()))

    NoOpCliktCommand(name = "wasmline")
        .subcommands(
            commands = arrayOf(
                Build(),
                Compile(),
                Manifest(),
                Download(),
                GenerateKeyPair(),
                Componentize(),
                tools,
                wit,
                component,
            ),
        )
        .versionOption(version = BuildConfig.VERSION)
        .main(argv = args)
}
