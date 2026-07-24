package crow.wasmline.cli

import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import crow.wasmline.extensions.printHeader

fun main(vararg args: String) {
    printHeader("wasmline command line")

    NoOpCliktCommand(name = "wasmline")
        .subcommands(
            commands = arrayOf(
                Build(),
                Compile(),
                Manifest(),
                Download(),
                GenerateKeyPair(),
            ),
        )
        .versionOption(version = BuildConfig.VERSION)
        .main(argv = args)
}
