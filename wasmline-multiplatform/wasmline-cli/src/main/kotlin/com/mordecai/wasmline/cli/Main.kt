package com.mordecai.wasmline.cli

import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import crow.mordecai.wasmline.extensions.printHeader
import crow.mordecai.wasmline.cli.BuildConfig

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
            )
        )
        .versionOption(version = BuildConfig.VERSION)
        .main(argv = args)
}