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
import com.github.ajalt.clikt.parameters.types.file
import crow.wasmline.plugin.core.manifest.ManifestKeyGenerator
import crow.wasmline.plugin.core.trust.PersistentKeyFiles
import java.io.File
import java.io.PrintStream

/**
 * Generates the Ed25519 key pair required by the current manifest format.
 *
 * The private key is printed only when it is not saved: `--save` writes both
 * keys to files and reports just the public key and the file paths, so build
 * logs never capture the signing key. The saved private key is restricted to
 * the owner on file systems that support POSIX permissions.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
internal class GenerateKeyPair(private val out: PrintStream = System.out) : CliktCommand(NAME) {
    private val save by option("-s", "--save")
        .flag(default = false)
        .help("Save keys to files in the output directory")

    private val outputDir by option("-o", "--output")
        .file(canBeFile = false, canBeDir = true)
        .default(File(DEFAULT_OUTPUT_DIR))
        .help("Output directory for key files. Default: $DEFAULT_OUTPUT_DIR")

    override fun run() {
        val keyPair = ManifestKeyGenerator.generate()
        val publicKeyHex = keyPair.publicKeyHex
        val privateKeyHex = keyPair.privateKeyHex

        if (!save) {
            out.println(
                """
      |ALGORITHM: Ed25519
      |PUBLIC KEY: $publicKeyHex
      |PRIVATE KEY: $privateKeyHex
                """.trimMargin(),
            )
            return
        }

        if (!outputDir.exists()) outputDir.mkdirs()
        val privateKeyFile = File(outputDir, PRIVATE_KEY_FILE_NAME)
        val publicKeyFile = File(outputDir, PUBLIC_KEY_FILE_NAME)
        PersistentKeyFiles.writePrivateKey(privateKeyFile, privateKeyHex)
        publicKeyFile.writeText(publicKeyHex)
        out.println(
            """
      |ALGORITHM: Ed25519
      |PUBLIC KEY: $publicKeyHex
            """.trimMargin(),
        )
        out.println("Private key saved to: ${privateKeyFile.absolutePath}")
        out.println("Public key saved to: ${publicKeyFile.absolutePath}")
    }

    /**
     * Defines stable command and output directory names.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    companion object {
        const val NAME = "generate-key-pair"
        const val DEFAULT_OUTPUT_DIR = "build/wasmline/keys"
        const val PRIVATE_KEY_FILE_NAME = "ed25519_private.key"
        const val PUBLIC_KEY_FILE_NAME = "ed25519_public.key"
    }
}
