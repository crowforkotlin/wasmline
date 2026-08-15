/*
 * Copyright (C) 2022 Block, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package crow.wasmline.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.file
import crow.wasmline.plugin.core.manifest.ManifestKeyGenerator
import crow.wasmline.plugin.core.manifest.ManifestSigningAlgorithm
import java.io.File
import java.io.PrintStream

internal class GenerateKeyPair(private val out: PrintStream = System.out) : CliktCommand(NAME) {

    private val algorithm by option("-a", "--algorithm")
        .enum<ManifestSigningAlgorithm>()
        .default(ManifestSigningAlgorithm.Ed25519)
        .help("Signing algorithm to use.")

    private val save by option("-s", "--save")
        .flag(default = false)
        .help("Save keys to files in the output directory")

    private val outputDir by option("-o", "--output")
        .file(canBeFile = false, canBeDir = true)
        .default(File(DEFAULT_OUTPUT_DIR))
        .help("Output directory for key files. Default: $DEFAULT_OUTPUT_DIR")

    override fun run() {
        val keyPair = ManifestKeyGenerator.generate(algorithm)
        val publicKeyHex = keyPair.publicKeyHex
        val privateKeyHex = keyPair.privateKeyHex

        out.println(
            """
      |ALGORITHM: $algorithm
      |PUBLIC KEY: $publicKeyHex
      |PRIVATE KEY: $privateKeyHex
            """.trimMargin(),
        )

        if (save) {
            if (!outputDir.exists()) outputDir.mkdirs()
            val algorithmName = algorithm.name.lowercase()
            val privateKeyFile = File(outputDir, "${algorithmName}_private.key")
            val publicKeyFile = File(outputDir, "${algorithmName}_public.key")
            privateKeyFile.writeText(privateKeyHex)
            publicKeyFile.writeText(publicKeyHex)
            out.println("Private key saved to: ${privateKeyFile.absolutePath}")
            out.println("Public key saved to: ${publicKeyFile.absolutePath}")
        }
    }

    companion object {
        const val NAME = "generate-key-pair"
        const val DEFAULT_OUTPUT_DIR = "build/wasmline/keys"
    }
}
