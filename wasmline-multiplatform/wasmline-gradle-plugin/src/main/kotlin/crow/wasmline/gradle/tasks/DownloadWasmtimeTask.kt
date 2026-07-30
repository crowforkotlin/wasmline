@file:Suppress("unused", "SpellCheckingInspection")

package crow.wasmline.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Gradle task for downloading wasmtime binary.
 * 
 * This task can be used independently or as part of the auto-download workflow.
 * 
 * Usage:
 * ```bash
 * ./gradlew wasmlineDownloadWasmtime
 * ```
 * 
 * With custom configuration:
 * ```kotlin
 * // In build.gradle.kts
 * tasks.named<DownloadWasmtimeTask>("wasmlineDownloadWasmtime") {
 *     version.set("v47.0.2")
 *     platform.set("x86_64-linux")
 *     outputDir.set(file("/custom/path/wasmtime"))
 * }
 * ```
 * 
 * 2026/7/30
 * @author crowforkotlin
 */
abstract class DownloadWasmtimeTask : DefaultTask() {

    init {
        group = "wasmline"
        description = "Download wasmtime binary"
    }

    @Inject
    protected abstract fun getObjects(): org.gradle.api.model.ObjectFactory

    /**
     * Wasmtime version to download.
     * Examples: "latest", "v47.0.2", "release-v47.0.2"
     */
    @get:Input
    abstract val version: Property<String>

    /**
     * Target platform/architecture.
     * Examples: "x86_64-linux", "aarch64-macos", "x86_64-windows"
     */
    @get:Input
    abstract val platform: Property<String>

    /**
     * Output directory for extracted wasmtime.
     */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /**
     * Optional: Explicit wasmtime directory (overrides outputDir behavior).
     * Used when passed from WasmlineExtension.
     */
    @get:InputDirectory
    @get:Optional
    abstract val wasmtimeDirectory: DirectoryProperty

    @TaskAction
    fun download() {
        val targetVersion = version.get()
        val targetPlatform = platform.get()
        val wasmtimeDirectoryConfig = wasmtimeDirectory.orNull
        val outputFile = if (wasmtimeDirectoryConfig != null) {
            wasmtimeDirectoryConfig.asFile
        } else {
            File(outputDir.get().asFile, "wasmtime-${targetVersion}-${targetPlatform}")
        }
        
        logger.lifecycle("========== Wasmtime Download ==========")
        logger.lifecycle("Version: $targetVersion")
        logger.lifecycle("Platform: $targetPlatform")
        logger.lifecycle("Output: ${outputFile.absolutePath}")
        logger.lifecycle("")

        // Check if already downloaded
        val executable = findWasmtimeExecutable(outputFile)
        if (executable != null) {
            logger.lifecycle("✅ wasmtime already exists at: ${executable.absolutePath}")
            val versionOutput = runCommand(listOf(executable.absolutePath, "--version"))
            logger.lifecycle("   Version info: ${versionOutput.trim().split("\n").first()}")
            return
        }

        // Attempt download
        logger.lifecycle("📥 Downloading wasmtime...")
        logger.lifecycle("")

        val success = when {
            // Method 1: Use embedded CLI (project internal only)
            project.rootProject.findProject(":wasmline-cli")?.tasks?.findByName("jar")?.outputs?.files?.firstOrNull()?.let { cliJar ->
                attemptCliDownload(cliJar, targetPlatform, targetVersion, outputFile)
            } != null -> true
            
            // Method 2: Global CLI
            tryGlobalDownload(targetPlatform, targetVersion, outputFile) -> true
            
            // Method 3: Provide manual instructions
            else -> {
                provideManualDownloadInstructions(targetPlatform, targetVersion)
                false
            }
        }

        if (!success) {
            throw GradleException(
                "Failed to download wasmtime for $targetPlatform v$targetVersion.\n" +
                "Please see the instructions above and download manually."
            )
        }

        logger.lifecycle("")
        logger.lifecycle("✅ Wasmtime downloaded successfully!")
        logger.lifecycle("   Location: ${outputFile.absolutePath}")
    }

    private fun attemptCliDownload(
        cliJar: File,
        platform: String,
        version: String,
        outputFile: File
    ): Boolean {
        project.logger.lifecycle("Method: Using embedded wasmline-cli JAR")
        project.logger.lifecycle("")
        
        val args = listOf(
            "java", "-jar", cliJar.absolutePath, "download",
            "-a", platform,
            "-v", version,
            "-o", outputFile.parentFile.absolutePath
        )
        
        project.logger.lifecycle("Executing: ${args.joinToString(" ")}")
        val process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .start()
        
        var hasProgress = false
        process.inputStream.bufferedReader().use { reader ->
            reader.forEachLine { line ->
                when {
                    line.contains("Downloading") || line.contains("Download:") -> {
                        project.logger.lifecycle("  📥 $line")
                        hasProgress = true
                    }
                    line.contains("Success") || line.contains("Skipping") || line.contains("Done") -> {
                        project.logger.lifecycle("  ✅ $line")
                        hasProgress = true
                    }
                    line.isNotEmpty() && !hasProgress -> {
                        project.logger.lifecycle("    $line")
                    }
                }
            }
        }
        
        val exitCode = process.waitFor()
        return exitCode == 0
    }

    private fun tryGlobalDownload(platform: String, version: String, outputFile: File): Boolean {
        project.logger.lifecycle("Method: Checking for global wasmline CLI")
        
        return try {
            val args = listOf("wasmline", "download", "-a", platform, "-v", version, "-o", outputFile.parentFile.absolutePath)
            val process = ProcessBuilder(args)
                .redirectErrorStream(true)
                .start()
            
            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    if (line.isNotEmpty()) {
                        project.logger.lifecycle("  $line")
                    }
                }
            }
            
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: IOException) {
            project.logger.lifecycle("⚠️  wasmline CLI not found in PATH")
            false
        }
    }

    private fun provideManualDownloadInstructions(platform: String, version: String) {
        val arch = platform.split("-")[0]
        val os = platform.split("-")[1]
        
        project.logger.warn("⚠️  No automatic download method available.")
        project.logger.lifecycle("")
        project.logger.lifecycle("💡 Please download manually:")
        project.logger.lifecycle("")
        project.logger.lifecycle("Step 1: Visit GitHub Releases")
        project.logger.lifecycle("  URL: https://github.com/crowforkotlin/wasmtime/releases")
        project.logger.lifecycle("  Or: https://github.com/wasmtime/wasmtime/releases")
        project.logger.lifecycle("")
        
        project.logger.lifecycle("Step 2: Find the right asset")
        val versionStr = version.removePrefix("v")
        project.logger.lifecycle("  Look for: wasmtime-v${versionStr}-$arch-$os-min.tar.xz")
        project.logger.lifecycle("")
        
        project.logger.lifecycle("Step 3: Download and extract")
        project.logger.lifecycle("  # Example for Linux x86_64:")
        project.logger.lifecycle("  wget https://github.com/crowforkotlin/wasmtime/releases/download/${version}/wasmtime-v${versionStr}-x86_64-linux-min.tar.xz")
        project.logger.lifecycle("  tar -xf wasmtime-v${versionStr}-x86_64-linux-min.tar.xz")
        project.logger.lifecycle("")
        
        project.logger.lifecycle("Step 4: Configure in build.gradle.kts")
        project.logger.lifecycle("""
            wasmline {
                wasmtime {
                    directory = file("/path/to/extracted/folder")
                }
            }
        """.trimIndent())
        project.logger.lifecycle("")
        
        project.logger.lifecycle("🔗 Quick links:")
        project.logger.lifecycle("  • Wasmtime Releases: https://github.com/wasmtime/wasmtime/releases")
        project.logger.lifecycle("  • Our Fork: https://github.com/crowforkotlin/wasmtime/releases")
    }

    private fun findWasmtimeExecutable(directory: File): File? {
        if (!directory.exists()) return null
        
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val candidates = if (isWindows) {
            listOf("wasmtime-min.exe", "wasmtime.exe")
        } else {
            listOf("wasmtime-min", "wasmtime")
        }
        
        return candidates.firstNotNullOfOrNull { name ->
            directory.walk().find { it.isFile && it.name.equals(name, ignoreCase = true) }
        }
    }

    private fun runCommand(command: List<String>): String {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        return output
    }
}
