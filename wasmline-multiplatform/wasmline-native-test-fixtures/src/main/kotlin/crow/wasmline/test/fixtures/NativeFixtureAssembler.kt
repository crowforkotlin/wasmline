package crow.wasmline.test.fixtures

import crow.wasmline.WasmlineComponentServiceContract
import crow.wasmline.loader.model.WasmlineRuntimeContract
import crow.wasmline.plugin.core.aot.AotCompatibilityCatalog
import crow.wasmline.plugin.core.aot.AotCompatibilitySelection
import crow.wasmline.plugin.core.aot.WasmlineAotBuildRequest
import crow.wasmline.plugin.core.aot.WasmlineAotBuildService
import crow.wasmline.plugin.core.aot.WasmlineArtifactTargetFactory
import crow.wasmline.plugin.core.component.ComponentPipeline
import crow.wasmline.plugin.core.component.ComponentizeRequest
import crow.wasmline.plugin.core.component.WasmToolsTool
import crow.wasmline.plugin.core.toolchain.ExternalToolRunner
import crow.wasmline.plugin.core.toolchain.ToolCache
import crow.wasmline.plugin.core.toolchain.ToolDownloader
import crow.wasmline.plugin.core.toolchain.ToolResolver
import crow.wasmline.plugin.core.toolchain.ToolchainCatalog
import crow.wasmline.plugin.core.toolchain.WasmlineTool
import crow.wasmline.plugin.core.util.PlatformDetector
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Builds every internal native test fixture from checked-in source files.
 *
 * Date: 2026-09-01
 * Author: crowforkotlin
 */
class NativeFixtureAssembler(
    private val fixtureSourceDirectory: File,
    private val directComponentFixture: File,
    private val componentServiceWitDirectory: File,
    private val toolCacheDirectory: File,
    private val compilerCacheDirectory: File,
    private val targets: List<String>,
    private val fixtureIds: List<String>,
    private val buildHost: String,
    private val autoDownload: Boolean,
    private val githubToken: String?,
    private val maxParallelCompilations: Int,
    private val logger: (String) -> Unit,
) {
    /** Builds fixture artifacts and writes a verified index below the supplied output directory. */
    fun assemble(outputDirectory: File): NativeFixtureIndex = runBlocking {
        require(fixtureSourceDirectory.isDirectory) { "Fixture source directory does not exist: ${fixtureSourceDirectory.absolutePath}" }
        require(directComponentFixture.isFile) { "Direct Component fixture does not exist: ${directComponentFixture.absolutePath}" }
        require(componentServiceWitDirectory.isDirectory) {
            "Wasmline Service WIT directory does not exist: ${componentServiceWitDirectory.absolutePath}"
        }
        require(targets.isNotEmpty()) { "Native fixture generation requires at least one target." }
        require(fixtureIds.distinct().size == fixtureIds.size) { "Native fixture IDs must not contain duplicates." }
        require(maxParallelCompilations > 0) { "Native fixture parallel compilation count must be positive." }
        check(outputDirectory.isDirectory || outputDirectory.mkdirs()) {
            "Unable to create native fixture output directory: ${outputDirectory.absolutePath}"
        }

        val targetSpecs = WasmlineArtifactTargetFactory.create(targets)
        val resolution = AotCompatibilityCatalog.resolveSelection(
            selection = AotCompatibilitySelection.Current,
            manifestMinimumWasmlineVersion = AotCompatibilityCatalog.releaseCatalog().minimumSupportedWasmlineVersion,
            artifactBackends = targetSpecs.map { spec -> spec.artifactBackend }.toSet(),
        )
        val downloader = ToolDownloader(logger = logger)
        try {
            val resolver = ToolResolver(ToolCache(toolCacheDirectory), downloader)
            val wasmToolsExecutable = resolver.resolve(
                tool = WasmlineTool.WASM_TOOLS,
                platform = PlatformDetector.detectPlatform(),
                autoDownload = autoDownload,
                githubToken = githubToken,
            ).file
            val toolRunner = ExternalToolRunner(logger = logger)
            val wasmTools = WasmToolsTool(wasmToolsExecutable, toolRunner)
            wasmTools.verify(ToolchainCatalog.WASM_TOOLS_VERSION)
            val workingDirectory = File(outputDirectory, ".work").apply { mkdirs() }
            try {
                val entries = selectedDefinitions().flatMap { definition ->
                    val inputFile = prepareInput(
                        definition = definition,
                        workingDirectory = workingDirectory,
                        wasmToolsExecutable = wasmToolsExecutable,
                        wasmTools = wasmTools,
                        toolRunner = toolRunner,
                        resolver = resolver,
                    )
                    compileFixture(
                        definition = definition,
                        inputFile = inputFile,
                        outputDirectory = outputDirectory,
                        workingDirectory = workingDirectory,
                        resolvedProfileIds = resolution.profiles.map { profile -> profile.id },
                        selectedGenerations = resolution.selectedGenerations,
                    )
                }
                val index = NativeFixtureIndex(fixtures = entries.sortedWith(NATIVE_FIXTURE_ARTIFACT_ORDER))
                NativeFixtureIndexes.write(index, File(outputDirectory, NativeFixtureIndexes.FILE_NAME))
                NativeFixtureIndexes.validateArtifacts(index, outputDirectory)
                index
            } finally {
                workingDirectory.deleteRecursively()
            }
        } finally {
            downloader.close()
        }
    }

    private suspend fun prepareInput(
        definition: NativeFixtureDefinition,
        workingDirectory: File,
        wasmToolsExecutable: File,
        wasmTools: WasmToolsTool,
        toolRunner: ExternalToolRunner,
        resolver: ToolResolver,
    ): File = when (definition.sourceKind) {
        NativeFixtureSourceKind.CORE_WAT,
        NativeFixtureSourceKind.COMPONENT_WAT,
        -> parseWat(definition, workingDirectory, wasmToolsExecutable, toolRunner).also(wasmTools::validate)

        NativeFixtureSourceKind.COMPONENT_WASM -> copyDirectComponent(definition, workingDirectory).also(wasmTools::validate)

        NativeFixtureSourceKind.COMPONENT_SERVICE_CORE_WAT -> componentizeService(
            definition = definition,
            workingDirectory = workingDirectory,
            wasmToolsExecutable = wasmToolsExecutable,
            wasmTools = wasmTools,
            toolRunner = toolRunner,
            resolver = resolver,
        )

        NativeFixtureSourceKind.RUST_COMPONENT -> buildRustComponent(definition, workingDirectory, wasmTools, toolRunner)
    }

    private fun parseWat(
        definition: NativeFixtureDefinition,
        workingDirectory: File,
        wasmToolsExecutable: File,
        toolRunner: ExternalToolRunner,
    ): File {
        val source = sourceFile(definition)
        val output = File(workingDirectory, "${definition.id}.wasm")
        toolRunner.run(
            executable = wasmToolsExecutable,
            arguments = listOf("parse", source.absolutePath, "-o", output.absolutePath),
            workingDirectory = workingDirectory,
        )
        require(output.isFile && output.length() > 0) { "wasm-tools parse did not produce ${output.absolutePath}." }
        return output
    }

    private suspend fun componentizeService(
        definition: NativeFixtureDefinition,
        workingDirectory: File,
        wasmToolsExecutable: File,
        wasmTools: WasmToolsTool,
        toolRunner: ExternalToolRunner,
        resolver: ToolResolver,
    ): File {
        val coreWasm = parseWat(definition, workingDirectory, wasmToolsExecutable, toolRunner)
        val adapter = resolver.resolve(
            tool = WasmlineTool.WASI_PREVIEW1_REACTOR_ADAPTER,
            version = ToolchainCatalog.WASI_PREVIEW1_ADAPTER_VERSION,
            platform = ToolchainCatalog.UNIVERSAL_PLATFORM,
            autoDownload = autoDownload,
            githubToken = githubToken,
        ).file
        val componentDirectory = File(workingDirectory, definition.id)
        val result = ComponentPipeline(wasmTools).componentize(
            ComponentizeRequest(
                coreWasm = coreWasm,
                witPath = componentServiceWitDirectory,
                wasiPreview1Adapter = adapter,
                outputDirectory = componentDirectory,
                productName = definition.id,
                world = "plugin",
                invocationProtocol = definition.runtimeContract.invocationProtocol,
                exportName = definition.runtimeContract.exportName,
                codec = definition.runtimeContract.contractMetadata[WasmlineComponentServiceContract.METADATA_CODEC],
                serviceProtocolVersion = definition.runtimeContract.contractMetadata[WasmlineComponentServiceContract.METADATA_VERSION],
                adapterVersion = ToolchainCatalog.WASI_PREVIEW1_ADAPTER_VERSION,
            ),
        )
        return result.componentWasm
    }

    private fun copyDirectComponent(definition: NativeFixtureDefinition, workingDirectory: File): File {
        val output = File(workingDirectory, "${definition.id}.component.wasm")
        directComponentFixture.copyTo(output, overwrite = true)
        return output
    }

    private fun buildRustComponent(
        definition: NativeFixtureDefinition,
        workingDirectory: File,
        wasmTools: WasmToolsTool,
        toolRunner: ExternalToolRunner,
    ): File {
        val sourceDirectory = sourceFile(definition).parentFile
        val cargo = findExecutable("cargo")
        val targetDirectory = File(workingDirectory, "${definition.id}-cargo-target")
        toolRunner.run(
            executable = cargo,
            arguments = listOf(
                "build",
                "--locked",
                "--release",
                "--target",
                RUST_COMPONENT_TARGET,
                "--manifest-path",
                File(sourceDirectory, "Cargo.toml").absolutePath,
            ),
            workingDirectory = sourceDirectory,
            environment = mapOf("CARGO_TARGET_DIR" to targetDirectory.absolutePath),
            timeoutMillis = RUST_COMPONENT_TIMEOUT_MILLIS,
        )
        val produced = File(
            targetDirectory,
            "$RUST_COMPONENT_TARGET/release/wasmline_resource_rust_fixture.wasm",
        )
        require(produced.isFile && produced.length() > 0) {
            "Rust Component fixture did not produce ${produced.absolutePath}. Install the $RUST_COMPONENT_TARGET target with rustup."
        }
        wasmTools.validate(produced)
        return produced
    }

    private suspend fun compileFixture(
        definition: NativeFixtureDefinition,
        inputFile: File,
        outputDirectory: File,
        workingDirectory: File,
        resolvedProfileIds: List<String>,
        selectedGenerations: List<Int>,
    ): List<NativeFixtureArtifact> {
        val packageDirectory = File(outputDirectory, "fixtures/${definition.id}")
        val record = WasmlineAotBuildService().build(
            WasmlineAotBuildRequest(
                inputFile = inputFile,
                packageDirectory = packageDirectory,
                workingDirectory = File(workingDirectory, "${definition.id}-aot"),
                runtimeContract = definition.runtimeContract,
                targets = targets,
                resolvedProfileIds = resolvedProfileIds,
                aotCompatibilitySelector = AotCompatibilitySelection.Current.diagnosticName(),
                selectedAotGenerations = selectedGenerations,
                publishRawWasm = false,
                compilerCacheDirectory = compilerCacheDirectory,
                buildHost = buildHost,
                autoDownload = autoDownload,
                githubToken = githubToken,
                maxParallelCompilations = maxParallelCompilations,
                logger = logger,
            ),
        )
        return record.compiledOutputs.map { output ->
            val profileId = requireNotNull(output.aotCompatibilityProfileId) {
                "Native fixture '${definition.id}' produced an artifact without an AOT compatibility profile."
            }
            val backend = requireNotNull(output.artifactBackend) {
                "Native fixture '${definition.id}' produced an artifact without an AOT backend."
            }
            NativeFixtureArtifact(
                fixtureId = definition.id,
                executionModel = definition.runtimeContract.executionModel.name,
                invocationProtocol = definition.runtimeContract.invocationProtocol.name,
                artifactFormat = output.format.name,
                artifactBackend = backend.name,
                requestedTarget = output.requestedTarget,
                normalizedTarget = output.normalizedTarget,
                aotCompatibilityProfileId = profileId,
                sha256 = output.sha256,
                sizeBytes = output.sizeBytes,
                relativePath = File("fixtures/${definition.id}", output.contentRelativePath).invariantSeparatorsPath,
            )
        }
    }

    /** Returns every fixture by default or the explicit fixture subset requested by the Gradle task. */
    private fun selectedDefinitions(): List<NativeFixtureDefinition> = if (fixtureIds.isEmpty()) {
        NativeFixtureDefinitions.all
    } else {
        fixtureIds.map(NativeFixtureDefinitions::require)
    }

    private fun sourceFile(definition: NativeFixtureDefinition): File {
        val relativePath = requireNotNull(definition.sourceRelativePath)
        val root = fixtureSourceDirectory.toPath().toAbsolutePath().normalize()
        val resolved = root.resolve(relativePath).normalize()
        require(resolved.startsWith(root)) { "Fixture '${definition.id}' source escapes ${fixtureSourceDirectory.absolutePath}." }
        val file = resolved.toFile()
        require(file.isFile) { "Fixture '${definition.id}' source does not exist: ${file.absolutePath}" }
        return file
    }

    private fun findExecutable(name: String): File {
        val configured = System.getenv(name.uppercase())?.takeIf(String::isNotBlank)?.let(::File)
        if (configured?.isFile == true && configured.canExecute()) return configured
        val candidates = System.getenv("PATH").orEmpty()
            .split(File.pathSeparator)
            .filter(String::isNotBlank)
            .map { directory -> File(directory, name) }
        return candidates.firstOrNull { candidate -> candidate.isFile && candidate.canExecute() }
            ?: error("Required executable '$name' was not found on PATH.")
    }

    /** Defines deterministic ordering for generated fixture artifacts. */
    private companion object {
        const val RUST_COMPONENT_TARGET: String = "wasm32-wasip2"
        const val RUST_COMPONENT_TIMEOUT_MILLIS: Long = 600_000

        val NATIVE_FIXTURE_ARTIFACT_ORDER: Comparator<NativeFixtureArtifact> = compareBy(
            NativeFixtureArtifact::fixtureId,
            NativeFixtureArtifact::artifactFormat,
            NativeFixtureArtifact::requestedTarget,
            NativeFixtureArtifact::aotCompatibilityProfileId,
        )
    }
}
