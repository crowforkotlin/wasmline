package com.mordecai.wasmline.cli

import com.github.ajalt.clikt.core.CliktCommand

/**
 * Compile task
 * 
 * 2026/1/20 00:16
 * @author crowforkotlin
 * @formatter:on
 */
class Compile : CliktCommand(name="compile") {
    override fun run() {
        println("compile")
    }
}