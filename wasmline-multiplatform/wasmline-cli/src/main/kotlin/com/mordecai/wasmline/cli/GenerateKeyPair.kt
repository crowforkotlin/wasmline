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

@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.mordecai.wasmline.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.mordecai.wasmline.loader.internal.crypto.*
import java.io.PrintStream

class GenerateKeyPair(
  private val out: PrintStream = System.out,
) : CliktCommand(NAME) {
  private val algorithm by option("-a", "--algorithm")
    .enum<SignatureAlgorithmId>()
    .default(SignatureAlgorithmId.Ed25519)
    .help("Signing algorithm to use.")

  override fun run() {
    val keyPair: com.mordecai.wasmline.loader.internal.crypto.KeyPair = generateKeyPair(algorithm)
    out.println(
      """
      |ALGORITHM: $algorithm
      |PUBLIC KEY: ${keyPair.publicKey.hex()}
      |PRIVATE KEY: ${keyPair.privateKey.hex()}
      """.trimMargin(),
    )
  }

  companion object {
    const val NAME = "generate-key-pair"
  }
}
