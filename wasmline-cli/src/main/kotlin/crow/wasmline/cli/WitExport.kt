package crow.wasmline.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import crow.wasmline.plugin.core.component.WasmlineServiceWit
import crow.wasmline.plugin.core.component.WitDigest
import java.io.File

/** Writes the canonical `wasmline:service` WIT so non-Kotlin guests can bind it. */
internal class WitExport : CliktCommand(name = "export") {
    private val outputDirectory by option("-o", "--output")
        .file(canBeFile = false, canBeDir = true)
        .default(File("build/wasmline/wit"))

    override fun run() {
        try {
            val written = WasmlineServiceWit.writeTo(outputDirectory)
            echo("WIT written to: " + written.absolutePath)
            echo("WIT SHA-256: " + WitDigest.sha256(written))
        } catch (error: Exception) {
            echo("Error: " + error.message, err = true)
            throw ProgramResult(1)
        }
    }
}
