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
import okio.Buffer
import org.junit.Test
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies CLI key-pair generation, output formatting, and key file permissions.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
class GenerateKeyPairTest {
    private val systemOut = Buffer()

    @Test fun happyPathDefault() {
        runCommand()
        assertLineMatches(systemOut.readUtf8Line(), "ALGORITHM: Ed25519")
        assertLineMatches(systemOut.readUtf8Line(), "PUBLIC KEY: [\\da-f]{64}")
        assertLineMatches(systemOut.readUtf8Line(), "PRIVATE KEY: [\\da-f]{64}")
        assertNull(systemOut.readUtf8Line())
    }

    @Test fun saveWritesBothKeysAndOmitsThePrivateKeyFromOutput() {
        val outputDirectory = createTempDirectory("wasmline-keys").toFile()
        try {
            runCommand("--save", "-o", outputDirectory.absolutePath)

            val lines = systemOut.readUtf8().lines().filter(String::isNotBlank)
            assertTrue(lines.any { it.startsWith("ALGORITHM: Ed25519") }, "missing algorithm line: $lines")
            assertTrue(lines.any { PUBLIC_KEY_LINE.matches(it) }, "missing public key line: $lines")
            assertFalse(
                lines.any { it.startsWith("PRIVATE KEY:") },
                "the saved private key must not be printed: $lines",
            )
            assertTrue(lines.any { it.contains("Private key saved to:") }, "missing private key path: $lines")

            val privateKeyFile = File(outputDirectory, GenerateKeyPair.PRIVATE_KEY_FILE_NAME)
            val publicKeyFile = File(outputDirectory, GenerateKeyPair.PUBLIC_KEY_FILE_NAME)
            assertEquals(PUBLIC_KEY_FILE_PATTERN.matches(publicKeyFile.readText()), true)
            assertEquals(PRIVATE_KEY_FILE_PATTERN.matches(privateKeyFile.readText()), true)
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    @Test fun saveRestrictsThePrivateKeyToTheOwner() {
        val outputDirectory = createTempDirectory("wasmline-keys-mode").toFile()
        try {
            if (!supportsPosixPermissions(outputDirectory)) return
            runCommand("--save", "-o", outputDirectory.absolutePath)

            val permissions = Files.getPosixFilePermissions(
                File(outputDirectory, GenerateKeyPair.PRIVATE_KEY_FILE_NAME).toPath(),
            )
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions)
        } finally {
            outputDirectory.deleteRecursively()
        }
    }

    private fun supportsPosixPermissions(directory: File): Boolean =
        runCatching { Files.getFileStore(directory.toPath()).supportsFileAttributeView("posix") }.getOrDefault(false)

    private fun assertLineMatches(line: String?, pattern: String) {
        assertNotNull(line, "Expected line to match '$pattern' but was null")
        assertTrue(Regex(pattern).matches(line), "Expected line: <$line> to match regex: <$pattern>")
    }

    private fun runCommand(vararg args: String) {
        val command = GenerateKeyPair(PrintStream(systemOut.outputStream()))
        command.parse(args.toList())
    }

    private companion object {
        val PUBLIC_KEY_LINE = Regex("PUBLIC KEY: [\\da-f]{64}")
        val PUBLIC_KEY_FILE_PATTERN = Regex("[\\da-f]{64}")
        val PRIVATE_KEY_FILE_PATTERN = Regex("[\\da-f]{64}")
    }
}
