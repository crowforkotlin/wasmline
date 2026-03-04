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
@file:Suppress("SpellCheckingInspection")

package crow.wasmline.cli

import com.github.ajalt.clikt.core.parse
import crow.wasmline.cli.GenerateKeyPair
import okio.Buffer
import org.junit.Test
import java.io.PrintStream
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenerateKeyPairTest {
  private val systemOut = Buffer()

  @Test fun happyPathDefault() {
    runCommand()
    assertLineMatches(systemOut.readUtf8Line(), "ALGORITHM: Ed25519")
    assertLineMatches(systemOut.readUtf8Line(), "PUBLIC KEY: [\\da-f]{64}")
    assertLineMatches(systemOut.readUtf8Line(), "PRIVATE KEY: [\\da-f]{64}")
    assertNull(systemOut.readUtf8Line())
  }

  @Test fun happyPathEd25519() {
    runCommand("-a", "Ed25519")
    assertLineMatches(systemOut.readUtf8Line(), "ALGORITHM: Ed25519")
    assertLineMatches(systemOut.readUtf8Line(), "PUBLIC KEY: [\\da-f]{64}")
    assertLineMatches(systemOut.readUtf8Line(), "PRIVATE KEY: [\\da-f]{64}")
    assertNull(systemOut.readUtf8Line())
  }

  @Test fun happyPathEcdsaP256() {
    runCommand("-a", "EcdsaP256")
    assertLineMatches(systemOut.readUtf8Line(), "ALGORITHM: EcdsaP256")
    assertLineMatches(systemOut.readUtf8Line(), "PUBLIC KEY: [\\da-f]{130}")
    assertLineMatches(systemOut.readUtf8Line(), "PRIVATE KEY: [\\da-f]{134}")
    assertNull(systemOut.readUtf8Line())
  }

  private fun assertLineMatches(line: String?, pattern: String) {
    assertNotNull(line, "Expected line to match '$pattern' but was null")
    assertTrue(Regex(pattern).matches(line), "Expected line: <$line> to match regex: <$pattern>")
  }

  private fun runCommand(vararg args: String) {
    val command = GenerateKeyPair(PrintStream(systemOut.outputStream()))
    command.parse(args.toList())
  }
}