@file:Suppress("SpellCheckingInspection")

package crow.wasmline.gradle.tasks

import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.plugin.core.compiler.WasmtimeCompiler
import crow.wasmline.plugin.core.manifest.ManifestSigner
import crow.wasmline.plugin.core.packaging.PluginPackager
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File

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

    @get:Input
    abstract val executionModel: Property<WasmlineExecutionModel>

    @get:Input
    abstract val invocationProtocol: Property<WasmlineInvocationProtocol>

    @get:Input
    @get:Optional
    abstract val exportName: Property<String>

    @get:Input
    abstract val contractMetadata: MapProperty<String, String>

    // ==================== Wasmtime config ====================

    /**
     * Base directory for locating the `wasmtime` executable.
     * Marked as @Internal because the directory may not exist at configuration
     * time and is resolved at execution time with sub-directory search.
     */
    @get:Internal
    abstract val wasmtimeDirectory: DirectoryProperty

    /** Target architectures for AOT compilation. Empty means all default targets. */
    @get:Input
    abstract val compileTargets: ListProperty<String>

    /** Required Wasmtime version used for AOT compilation. */
    @get:Input
    abstract val wasmtimeVersion: Property<String>

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
                    "Ensure the Kotlin/WasmWasi compilation task for variant '$variant' has run successfully.",
            )

        val productName = pId.substringAfterLast('.')

        logger.info("Wasmline assemble: plugin=$pId, version=$pVersion, variant=$variant")
        logger.info("Wasm input: ${wasmFile.absolutePath}")
        logger.info("Wasmline output: ${outDir.absolutePath}")

        // Resolve wasmtime base directory (DSL > env > default)
        val wasmtimeBaseDir = wasmtimeDirectory.orNull?.asFile
            ?: System.getenv("WASMTIME_ROOT")?.let { File(it) }
            ?: File(System.getProperty("user.home"), ".wasmline/wasmtime")
        logger.info("Wasmtime base directory: ${wasmtimeBaseDir.absolutePath}")

        // Search for executable (direct + versioned subdirectories)
        val wasmtimeExec = WasmtimeCompiler.findWasmtimeInDirectory(
            baseDir = wasmtimeBaseDir,
            version = wasmtimeVersion.get(),
        )
            ?: throw GradleException(
                "wasmtime ${wasmtimeVersion.get()} executable not found in '${wasmtimeBaseDir.absolutePath}' or its subdirectories.\n" +
                    "Run './gradlew wasmlineDownloadWasmtime' or 'wasmline download -a <platform>' first.\n" +
                    "\n" +
                    "Common locations to check:\n" +
                    "  1. ~/.wasmline/wasmtime/\n" +
                    "  2. Custom path configured in build.gradle.kts\n" +
                    "  3. WASMTIME_ROOT environment variable",
            )

        logger.info("Wasmtime executable: ${wasmtimeExec.absolutePath}")

        val artifacts = WasmtimeCompiler().compileAll(
            wasmtimeExec = wasmtimeExec,
            inputWasm = wasmFile,
            outputDir = outDir,
            productName = productName,
            targets = compileTargets.get(),
            logger = ::logCompilerMessage,
        )

        if (artifacts.isEmpty()) {
            throw GradleException("No artifacts produced by wasmtime compilation. Check wasmtime installation.")
        }

        WasmtimeCompiler().writeCompileResult(
            inputFile = wasmFile,
            debugDir = debugDir,
            artifacts = artifacts,
            wasmtimeVersion = wasmtimeExec.name,
        )

        val wlmFile = ManifestSigner().createSignedManifest(
            artifacts = artifacts,
            pluginId = pId,
            version = pVersion,
            versionCode = versionCode.get(),
            minSdkVersion = minSdkVersion.get(),
            signingKey = signingKey.get(),
            outputDir = outDir,
            displayName = displayName.orNull,
            author = author.orNull,
            description = pluginDescription.orNull,
            iconUrl = iconUrl.orNull,
            homePageUrl = homePageUrl.orNull,
            metadata = metadata.get(),
            executionModel = executionModel.get(),
            invocationProtocol = invocationProtocol.get(),
            exportName = exportName.orNull,
            contractMetadata = contractMetadata.get(),
            logger = logger::info,
        )

        val distDir = File(outputDir.get().asFile.parentFile.parentFile, "dist").apply { mkdirs() }
        val zipName = "$pId-$pVersion.zip"
        val zipFile = File(distDir, zipName)
        val folderPrefix = "$pId-$pVersion"

        PluginPackager.createZip(wlmFile, artifacts, outDir, zipFile, folderPrefix)

        logger.info("Wasmline package: ${zipFile.absolutePath} (${zipFile.length()} bytes)")
        logger.info("Wasmline assemble completed: ${artifacts.size} artifacts")
    }

    /** Writes compiler errors at error level and routine messages at info level. */
    private fun logCompilerMessage(message: String) {
        if (message.contains("error", ignoreCase = true) || message.startsWith("Failed")) {
            logger.error(message)
        } else {
            logger.info(message)
        }
    }
}
