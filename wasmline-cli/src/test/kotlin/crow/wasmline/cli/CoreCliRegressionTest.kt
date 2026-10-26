package crow.wasmline.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import crow.wasmline.RawExportKind
import crow.wasmline.RawValueType
import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.plugin.core.aot.AotCompatibilityCatalog
import crow.wasmline.plugin.core.aot.WasmlineAotBuildRecords
import crow.wasmline.plugin.core.manifest.ManifestKeyGenerator
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies that the CLI exposes only catalog-backed AOT compilation options.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
class CoreCliRegressionTest {
    @Test
    fun `compile rejects removed local Wasmtime directory options`() = withCoreCliDirectory { root ->
        val input = File(root, "plugin.wasm").apply { writeCoreWasmFixture() }

        listOf("--wasmtime", "--arch", "--compiler-executable").forEach { removedOption ->
            assertFailsWith<UsageError> {
                Compile().parse(
                    listOf(
                        "--input",
                        input.absolutePath,
                        removedOption,
                        "unused",
                    ),
                )
            }
        }
    }

    @Test
    fun `removed AOT selector and min sdk flags are usage errors`() = withCoreCliDirectory { root ->
        val input = File(root, "plugin.wasm").apply { writeCoreWasmFixture() }
        val key = File(root, "ed25519_private.key").apply {
            writeText(ManifestKeyGenerator.generate().privateKeyHex)
        }

        for (removedArguments in listOf(
            listOf("--aot-compatibility", "current"),
            listOf("--aot-version-range", "1.0.0..1.0.1"),
            listOf("--min-sdk", "1.0.0"),
        )) {
            assertFailsWith<UsageError> {
                Compile().parse(listOf("--input", input.absolutePath) + removedArguments)
            }
            assertFailsWith<UsageError> {
                Build().parse(
                    listOf(
                        "--input",
                        input.absolutePath,
                        "--key",
                        key.absolutePath,
                        "--plugin-id",
                        "crow.wasmline.test.usage",
                    ) + removedArguments,
                )
            }
            assertFailsWith<UsageError> {
                Manifest().parse(listOf("--dir", root.absolutePath, "--key", key.absolutePath) + removedArguments)
            }
        }
    }

    @Test
    fun `compile rejects out of catalog generations before touching outputs`() = withCoreCliDirectory { root ->
        val input = File(root, "plugin.wasm").apply { writeCoreWasmFixture() }
        val outputRoot = File(root, "output")
        val compilerCache = File(root, "compiler-cache")
        File(outputRoot, "plugin-1.0.0").apply {
            mkdirs()
            File(this, SENTINEL_NAME).writeText("package")
            File(this, WasmlineAotBuildRecords.FILE_NAME).writeText("""{"aotCompatibilitySelector":"generations"}""")
        }
        File(compilerCache, "sentinel.txt").apply {
            parentFile.mkdirs()
            writeText("cache")
        }
        val outputSnapshot = outputRoot.snapshot()
        val cacheSnapshot = compilerCache.snapshot()
        val catalog = AotCompatibilityCatalog.releaseCatalog()
        val outOfBounds = successorAotVersion(catalog.currentWasmlineVersion)
        val invalidSelections = listOf(
            "${catalog.minimumSupportedWasmlineVersion}..$outOfBounds" to outOfBounds,
            "current:after=${catalog.ranges.size}" to "after=${catalog.ranges.size}",
        )

        for ((invalidSelection, expectedToken) in invalidSelections) {
            val command = Compile()
            val echoed = command.captureEcho()
            val failure = assertFailsWith<ProgramResult> {
                command.parse(
                    listOf(
                        "--input",
                        input.absolutePath,
                        "--output",
                        outputRoot.absolutePath,
                        "--name",
                        "plugin",
                        "--version",
                        "1.0.0",
                        "--aot-compiler-cache",
                        compilerCache.absolutePath,
                        "--target",
                        "pulley64",
                        "--aot-generations",
                        invalidSelection,
                    ),
                )
            }

            assertEquals(1, failure.statusCode)
            assertSelectionFailure(echoed, expectedToken)
            assertEquals(outputSnapshot, outputRoot.snapshot())
            assertEquals(cacheSnapshot, compilerCache.snapshot())
            assertEquals(emptyList(), outputRoot.stagingDirectories("plugin-1.0.0"))
        }
    }

    @Test
    fun `build rejects invalid generations before publishing a package`() {
        // Build resolves its package directory and ZIP below the process working directory, so this
        // case uses a test-owned plugin id and asserts that a rejected selection publishes nothing.
        val packageId = "crow.wasmline.test.order.${UUID.randomUUID().toString().replace("-", "")}"
        val folderName = "$packageId-1.0.0"
        val buildRoot = File("build/wasmline")
        val outputRoot = File(buildRoot, "output")
        val distributionDirectory = File(buildRoot, "dist")
        val packageDirectory = File(outputRoot, folderName)
        val archive = File(distributionDirectory, "$folderName.zip")
        val catalog = AotCompatibilityCatalog.releaseCatalog()
        val minimumVersion = catalog.minimumSupportedWasmlineVersion
        val currentVersion = catalog.currentWasmlineVersion
        val aboveCurrent = successorAotVersion(currentVersion)
        val belowMinimum = predecessorAotVersion(minimumVersion)
        val invalidSelections = listOf(
            "$minimumVersion..$aboveCurrent" to aboveCurrent,
            "$belowMinimum..$minimumVersion" to belowMinimum,
            "$currentVersion..$belowMinimum" to currentVersion,
            "current:after=${catalog.ranges.size}" to "after=${catalog.ranges.size}",
            "latest:before=${catalog.ranges.size}" to "before=${catalog.ranges.size}",
            "current:before=01" to "before=01",
        )
        val buildRootExisted = buildRoot.exists()
        val outputRootExisted = outputRoot.exists()
        val distributionDirectoryExisted = distributionDirectory.exists()

        try {
            withCoreCliDirectory { root ->
                val input = File(root, "plugin.wasm").apply { writeCoreWasmFixture() }
                val key = File(root, "ed25519_private.key").apply {
                    writeText(ManifestKeyGenerator.generate().privateKeyHex)
                }
                val compilerCache = File(root, "compiler-cache")
                File(compilerCache, "sentinel.txt").apply {
                    parentFile.mkdirs()
                    writeText("cache")
                }
                packageDirectory.apply {
                    mkdirs()
                    File(this, SENTINEL_NAME).writeText("package")
                    File(this, WasmlineAotBuildRecords.FILE_NAME).writeText("""{"aotCompatibilitySelector":"generations"}""")
                }
                archive.apply {
                    parentFile.mkdirs()
                    writeText("archive")
                }
                val packageSnapshot = packageDirectory.snapshot()
                val archiveSnapshot = archive.snapshot()
                val outputListing = outputRoot.listNames()
                val distributionListing = distributionDirectory.listNames()
                val cacheSnapshot = compilerCache.snapshot()

                for ((invalidSelection, expectedToken) in invalidSelections) {
                    val command = Build()
                    val echoed = command.captureEcho()
                    val failure = assertFailsWith<ProgramResult> {
                        command.parse(
                            listOf(
                                "--input",
                                input.absolutePath,
                                "--name",
                                "order",
                                "--version",
                                "1.0.0",
                                "--plugin-id",
                                packageId,
                                "--key",
                                key.absolutePath,
                                "--aot-compiler-cache",
                                compilerCache.absolutePath,
                                "--target",
                                "pulley64",
                                "--aot-generations",
                                invalidSelection,
                            ),
                        )
                    }

                    assertEquals(1, failure.statusCode)
                    assertSelectionFailure(echoed, expectedToken)
                    assertEquals(packageSnapshot, packageDirectory.snapshot())
                    assertEquals(archiveSnapshot, archive.snapshot())
                    assertEquals(outputListing, outputRoot.listNames())
                    assertEquals(distributionListing, distributionDirectory.listNames())
                    assertEquals(cacheSnapshot, compilerCache.snapshot())
                    assertEquals(emptyList(), outputRoot.stagingDirectories(folderName))
                }
            }
        } finally {
            packageDirectory.deleteRecursively()
            archive.delete()
            outputRoot.deleteIfEmpty(outputRootExisted)
            distributionDirectory.deleteIfEmpty(distributionDirectoryExisted)
            buildRoot.deleteIfEmpty(buildRootExisted)
        }
    }

    @Test
    fun `invocation options load canonical raw ABI metadata`() = withCoreCliDirectory { root ->
        val rawAbiFile = File(root, "raw-abi.json").apply {
            writeText(
                """
                {
                  "version": 1,
                  "exports": [
                    {
                      "name": "add_i32",
                      "kind": "FUNCTION",
                      "signature": {
                        "parameters": ["I32", "I32"],
                        "results": ["I32"]
                      }
                    }
                  ],
                  "imports": [],
                  "memoryExport": "memory",
                  "requiredFeatures": []
                }
                """.trimIndent(),
            )
        }

        val invocation = parseInvocationOptions(
            executionModelName = WasmlineExecutionModel.CORE_WASM.name,
            invocationProtocolName = WasmlineInvocationProtocol.RAW_EXPORT.name,
            exportName = "add_i32",
            contractMetadataEntries = emptyList(),
            rawAbiMetadataFile = rawAbiFile,
        )

        val export = requireNotNull(invocation.rawAbi).exports.single()
        assertEquals(RawExportKind.FUNCTION, export.kind)
        assertEquals(listOf(RawValueType.I32, RawValueType.I32), export.signature?.parameters)
        assertEquals(listOf(RawValueType.I32), export.signature?.results)
    }

    private companion object {
        const val SENTINEL_NAME = "wasmline-regression-sentinel.txt"
    }
}

/** Writes the smallest complete Core Wasm module so input validation can never mask selection errors. */
private fun File.writeCoreWasmFixture() {
    writeBytes(byteArrayOf(0, 97, 115, 109, 1, 0, 0, 0))
}

/** Captures this command's echoed output through its real context instead of the process streams. */
private fun CliktCommand.captureEcho(): StringBuilder {
    val builder = StringBuilder()
    configureContext {
        echoMessage = { _, message, trailingNewline, _ ->
            builder.append(message)
            if (trailingNewline) builder.append('\n')
        }
    }
    return builder
}

/** Asserts the captured stderr identifies a local AOT generation selection failure. */
private fun assertSelectionFailure(echoed: StringBuilder, expectedToken: String) {
    val message = echoed.toString()
    assertTrue(message.contains("AOT"), message)
    assertTrue(message.contains(expectedToken), message)
}

/** Returns one digest per regular file below this path, keyed by its relative path. */
private fun File.snapshot(): Map<String, String> {
    if (isFile) return mapOf(name to digest())
    if (!isDirectory) return emptyMap()
    return walkTopDown()
        .filter(File::isFile)
        .associate { file -> file.relativeTo(this).path to file.digest() }
}

private fun File.digest(): String = MessageDigest.getInstance("SHA-256").digest(readBytes())
    .joinToString("") { byte -> "%02x".format(byte) }

/** Returns every package staging directory for one destination prefix below this path. */
private fun File.stagingDirectories(destinationPrefix: String): List<String> {
    if (!isDirectory) return emptyList()
    return walkTopDown()
        .filter { file -> file.isDirectory && file.name.startsWith(".$destinationPrefix.staging-") }
        .map { file -> file.relativeTo(this).path }
        .sorted()
        .toList()
}

/** Returns the sorted direct children of this path, or an empty list when it does not exist. */
private fun File.listNames(): List<String> = if (isDirectory) listFiles().orEmpty().map(File::getName).sorted() else emptyList()

/** Removes this directory only when it is empty and did not exist before the test. */
private fun File.deleteIfEmpty(parentExisted: Boolean) {
    if (!parentExisted && isDirectory && listFiles().orEmpty().isEmpty()) delete()
}

private inline fun withCoreCliDirectory(block: (File) -> Unit) {
    val directory = createTempDirectory("wasmline-cli-core-regression-test").toFile()
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}
