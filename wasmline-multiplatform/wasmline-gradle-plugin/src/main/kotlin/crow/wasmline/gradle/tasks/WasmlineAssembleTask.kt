@file:Suppress("SpellCheckingInspection")

package crow.wasmline.gradle.tasks

import crow.wasmline.gradle.internal.ManifestBuilder
import crow.wasmline.gradle.internal.WasmtimeCompiler
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Gradle task that assembles a wasmline plugin package.
 *
 * The task performs the following steps:
 * 1. Locate the `.wasm` file produced by the Kotlin/WasmWasi compilation task.
 * 2. Run `wasmtime` AOT compilation for each configured target architecture.
 * 3. Build and sign the `manifest.wlm` (Protobuf-encoded + Ed25519).
 * 4. Package all artifacts into a distributable `.zip`.
 *
 * Two instances of this task are registered:
 * - `wasmlineAssembleDebug` (variant = "Development")
 * - `wasmlineAssembleRelease` (variant = "Production")
 *
 * 2026/6/5
 * @author crowforkotlin
 */
abstract class WasmlineAssembleTask : DefaultTask() {

    init {
        group = "wasmline"
        description = "Assemble wasmline plugin package"
    }

    // ==================== Build variant ====================

    /** "Development" or "Production". Determines which Kotlin compilation task output is used. */
    @get:Input
    abstract val buildVariant: Property<String>

    // ==================== Manifest metadata ====================

    @get:Input
    abstract val pluginId: Property<String>

    @get:Input
    abstract val pluginVersion: Property<String>

    @get:Input
    abstract val versionCode: Property<Long>

    @get:Input
    abstract val minSdkVersion: Property<String>

    @get:Input
    @get:Optional
    abstract val displayName: Property<String>

    @get:Input
    @get:Optional
    abstract val author: Property<String>

    /** Short description of the plugin (renamed to avoid clash with Task. description). */
    @get:Input
    @get:Optional
    abstract val pluginDescription: Property<String>

    @get:Input
    @get:Optional
    abstract val iconUrl: Property<String>

    @get:Input
    @get:Optional
    abstract val homePageUrl: Property<String>

    /** Ed25519 private key hex string or file path. */
    @get:Input
    abstract val signingKey: Property<String>

    @get:Input
    abstract val metadata: MapProperty<String, String>

    // ==================== Wasmtime config ====================

    /** Directory containing the `wasmtime` executable. */
    @get:InputDirectory
    abstract val wasmtimeDirectory: DirectoryProperty

    /** Target architectures for AOT compilation. Empty means all default targets. */
    @get:Input
    abstract val compileTargets: ListProperty<String>

    // ==================== Input / Output ====================

    /**
     * Directory containing the `.wasm` file produced by the Kotlin/WasmWasi
     * compilation task. The actual `.wasm` file is located at task execution
     * time (not at configuration time) because the compilation may not have
     * run yet when Gradle resolves the dependency graph.
     */
    @get:InputDirectory
    abstract val wasmCompileOutputDir: DirectoryProperty

    /** Root output directory for assembled artifacts. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    // ==================== Task action ====================

    @TaskAction
    fun assemble() {
        val variant = buildVariant.get()
        val pId = pluginId.get()
        val pVersion = pluginVersion.get()
        val outDir = File(outputDir.get().asFile, "$pId-$pVersion").apply { mkdirs() }
        val debugDir = File(outDir, "debug").apply { mkdirs() }

        // Resolve the .wasm file at execution time (after compilation has run).
        val compileDir = wasmCompileOutputDir.get().asFile
        val wasmFile = compileDir.listFiles { f -> f.extension == "wasm" }?.firstOrNull()
            ?: throw GradleException(
                "No .wasm file found in ${compileDir.absolutePath}. " +
                    "Ensure the Kotlin/WasmWasi compilation task for variant '$variant' has run successfully."
            )

        val productName = pId.substringAfterLast('.')

        logger.lifecycle("========== Wasmline Assemble ($variant) ==========")
        logger.lifecycle("Plugin ID: $pId")
        logger.lifecycle("Version: $pVersion")
        logger.lifecycle("Input: ${wasmFile.absolutePath}")
        logger.lifecycle("Output: ${outDir.absolutePath}")

        // -------- Step 1: Wasmtime AOT compilation --------
        logger.lifecycle("========== Step 1: Wasmtime Compile ==========")

        val wasmtimeExec = WasmtimeCompiler.resolveExecutable(wasmtimeDirectory.get().asFile)
        logger.lifecycle("Using wasmtime: ${wasmtimeExec.absolutePath}")

        val artifacts = WasmtimeCompiler.compileAll(
            wasmtimeExec = wasmtimeExec,
            inputFile = wasmFile,
            outputDir = outDir,
            productName = productName,
            targets = compileTargets.get(),
            logger = logger,
        )

        if (artifacts.isEmpty()) {
            throw GradleException("No artifacts produced by wasmtime compilation. Check wasmtime installation.")
        }

        WasmtimeCompiler.writeCompileResult(wasmFile, debugDir, artifacts)

        // -------- Step 2: Manifest build & sign --------
        logger.lifecycle("========== Step 2: Manifest ==========")

        val wlmFile = ManifestBuilder.buildAndSign(
            artifacts = artifacts,
            pluginId = pId,
            version = pVersion,
            versionCode = versionCode.get(),
            minSdkVersion = minSdkVersion.get(),
            displayName = displayName.orNull,
            author = author.orNull,
            description = pluginDescription.orNull,
            iconUrl = iconUrl.orNull,
            homePageUrl = homePageUrl.orNull,
            metadata = metadata.get(),
            signingKeyHex = signingKey.get(),
            outputDir = outDir,
            logger = logger,
        )

        // -------- Step 3: Package zip --------
        logger.lifecycle("========== Step 3: Package ==========")

        val distDir = File(outputDir.get().asFile.parentFile.parentFile, "dist").apply { mkdirs() }
        val zipName = "$pId-$pVersion.zip"
        val zipFile = File(distDir, zipName)
        val folderPrefix = "$pId-$pVersion"

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            addToZip(zos, wlmFile, "$folderPrefix/${wlmFile.name}")
            artifacts.forEach { artifact ->
                val file = File(outDir, artifact.url)
                if (file.exists()) {
                    addToZip(zos, file, "$folderPrefix/${file.name}")
                }
            }
        }

        logger.lifecycle("Package written to: ${zipFile.absolutePath} (${zipFile.length()} bytes)")
        logger.lifecycle("==========  Assemble Complete ($variant)  ==========")
        logger.lifecycle("Artifacts: ${artifacts.size}")
    }

    private fun addToZip(zos: ZipOutputStream, file: File, entryName: String) {
        zos.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(zos) }
        zos.closeEntry()
    }
}
