package crow.wasmline.test.fixtures

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Runs native fixture assembly from the Gradle JavaExec task. */
fun main(arguments: Array<String>) {
    val options = arguments.toList().chunked(2).associate { pair ->
        require(pair.size == 2 && pair[0].startsWith("--")) { "Fixture assembler arguments must be --name value pairs." }
        pair[0].removePrefix("--") to pair[1]
    }
    val outputDirectory = File(options.require("output"))
    val parentDirectory = requireNotNull(outputDirectory.parentFile)
    check(parentDirectory.isDirectory || parentDirectory.mkdirs()) {
        "Unable to create native fixture output parent: ${parentDirectory.absolutePath}"
    }
    val stagingDirectory = Files.createTempDirectory(parentDirectory.toPath(), ".native-fixtures-").toFile()
    try {
        require(File(options.require("aot-catalog")).isFile) { "AOT compatibility catalog is missing." }
        require(File(options.require("aot-lock")).isFile) { "AOT compatibility lock is missing." }
        require(File(options.require("toolchain-lock")).isFile) { "Toolchain lock is missing." }
        NativeFixtureAssembler(
            fixtureSourceDirectory = File(options.require("fixture-source")),
            directComponentFixture = File(options.require("direct-component")),
            componentServiceWitDirectory = File(options.require("service-wit")),
            toolCacheDirectory = File(System.getProperty("user.home"), ".wasmline/tools"),
            compilerCacheDirectory = File(System.getProperty("user.home"), ".wasmline/toolchains/wasmtime/compiler-assets"),
            targets = options.require("targets").split(',').map(String::trim).filter(String::isNotEmpty),
            fixtureIds = options["fixture-ids"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty),
            buildHost = options.require("build-host"),
            autoDownload = System.getProperty("wasmline.native.fixtures.autoDownload", "true").toBooleanStrict(),
            githubToken = System.getenv("GITHUB_TOKEN")?.takeIf(String::isNotBlank),
            maxParallelCompilations = System.getProperty("wasmline.native.fixtures.maxParallelCompilations", "1").toInt(),
            logger = ::println,
        ).assemble(stagingDirectory)
        replaceDirectory(stagingDirectory, outputDirectory)
        println("Native fixture index: ${File(outputDirectory, NativeFixtureIndexes.FILE_NAME).absolutePath}")
    } finally {
        if (stagingDirectory.exists()) stagingDirectory.deleteRecursively()
    }
}

/** Returns a required named command-line value. */
private fun Map<String, String>.require(name: String): String = get(name)?.takeIf(String::isNotBlank)
    ?: error("Fixture assembler argument '--$name' is required.")

/** Replaces the prior complete fixture output after assembly succeeds. */
private fun replaceDirectory(stagingDirectory: File, outputDirectory: File) {
    val parentDirectory = requireNotNull(outputDirectory.parentFile)
    val backupRoot = Files.createTempDirectory(parentDirectory.toPath(), ".native-fixtures-backup-").toFile()
    val backupDirectory = File(backupRoot, "previous")
    try {
        if (outputDirectory.exists()) moveDirectory(outputDirectory, backupDirectory)
        try {
            moveDirectory(stagingDirectory, outputDirectory)
        } catch (failure: Throwable) {
            if (backupDirectory.exists()) {
                runCatching { moveDirectory(backupDirectory, outputDirectory) }
                    .onFailure(failure::addSuppressed)
            }
            throw failure
        }
    } finally {
        if (backupRoot.exists()) backupRoot.deleteRecursively()
    }
}

/** Moves one complete directory, preferring an atomic filesystem operation. */
private fun moveDirectory(sourceDirectory: File, destinationDirectory: File) {
    runCatching {
        Files.move(sourceDirectory.toPath(), destinationDirectory.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }.getOrElse {
        Files.move(sourceDirectory.toPath(), destinationDirectory.toPath())
    }
}
