package crow.wasmline.plugin.core.component

import crow.wasmline.WasmlineExecutionModel
import crow.wasmline.WasmlineInvocationProtocol
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineArtifactType
import crow.wasmline.plugin.core.toolchain.FileDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val componentAotSemanticVersion = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
private val componentAotSha256 = Regex("[0-9a-fA-F]{64}")

/** Self-contained boundary between raw Component creation and native package assembly. */
@Serializable
data class ComponentAotBuildRecord(
    val rawComponent: ComponentBuildRecord,
    val inputComponentSha256: String,
    val wasmtimeVersion: String,
    val engineOptions: ComponentAotEngineOptions,
    val artifacts: List<WasmlineArtifact>,
) {
    init {
        require(inputComponentSha256.matches(componentAotSha256)) {
            "Component AOT input SHA-256 must contain 64 hexadecimal characters."
        }
        require(inputComponentSha256.equals(rawComponent.componentSha256, ignoreCase = true)) {
            "Component AOT input digest does not match the raw Component build record."
        }
        require(wasmtimeVersion.matches(componentAotSemanticVersion)) {
            "Component AOT Wasmtime version must use x.y.z: $wasmtimeVersion"
        }
        require(artifacts.isNotEmpty()) { "Component AOT build record must contain at least one artifact." }
        artifacts.forEach(::validateArtifact)
    }

    /** Resolves and verifies every artifact without using its filename to infer behavior. */
    fun resolveArtifacts(directory: File): List<ResolvedComponentAotArtifact> = artifacts.map { artifact ->
        val file = resolveChild(directory, artifact.url)
        require(file.isFile && file.length() > 0) {
            "Component AOT artifact does not exist or is empty: " + file.absolutePath
        }
        val actualDigest = FileDigest.sha256Hex(file)
        require(actualDigest.equals(artifact.sha256, ignoreCase = true)) {
            "Component AOT artifact SHA-256 mismatch for " + file.absolutePath +
                ": expected " + artifact.sha256 + ", actual " + actualDigest + "."
        }
        ResolvedComponentAotArtifact(artifact, file)
    }

    private fun validateArtifact(artifact: WasmlineArtifact) {
        require(artifact.type == WasmlineArtifactType.CWASM || artifact.type == WasmlineArtifactType.PWASM) {
            "Component AOT build records may contain only CWASM or PWASM artifacts, not ${artifact.type}."
        }
        require(artifact.type != WasmlineArtifactType.CWASM || artifact.targetOs != "ios") {
            "iOS Component artifacts must use portable PWASM, not CWASM."
        }
        require(artifact.executionModel == WasmlineExecutionModel.COMPONENT_MODEL) {
            "Component AOT build record artifacts must use executionModel=COMPONENT_MODEL."
        }
        require(artifact.invocationProtocol == WasmlineInvocationProtocol.COMPONENT_EXPORT) {
            "Component AOT build record artifacts must use invocationProtocol=COMPONENT_EXPORT."
        }
        require(artifact.targetCompilerVersion == "wasmtime-$wasmtimeVersion") {
            "Component AOT artifact compiler version must be wasmtime-$wasmtimeVersion."
        }
        require(artifact.sha256.matches(componentAotSha256)) {
            "Component AOT artifact SHA-256 must contain 64 hexadecimal characters."
        }
        require(artifact.url.isNotBlank()) { "Component AOT artifact URL must not be blank." }
        require(artifact.exportName == rawComponent.exportName) {
            "Component AOT artifact export does not match the raw Component build record."
        }
    }

    private fun resolveChild(directory: File, relativePath: String): File {
        val root = directory.toPath().toAbsolutePath().normalize()
        val resolved = root.resolve(relativePath).normalize()
        require(resolved.startsWith(root)) { "Component AOT artifact path escapes its output directory: $relativePath" }
        return resolved.toFile()
    }
}

/** Associates verified Component metadata with its concrete package file. */
data class ResolvedComponentAotArtifact(val artifact: WasmlineArtifact, val file: File)

/** Reads and writes native Component AOT build-stage records. */
object ComponentAotBuildRecords {
    const val FILE_NAME = "component-aot-result.json"

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    fun write(rawComponent: ComponentBuildRecord, result: ComponentAotCompileResult, outputFile: File): ComponentAotBuildRecord {
        require(result.inputComponentSha256.equals(rawComponent.componentSha256, ignoreCase = true)) {
            "Compiled Component digest does not match the raw Component build record."
        }
        val directory = outputFile.parentFile ?: error("Component AOT result file requires a parent directory.")
        check(directory.exists() || directory.mkdirs()) {
            "Unable to create Component AOT result directory: " + directory.absolutePath
        }

        val artifacts = result.outputs.map { output ->
            require(output.outputFile.isFile && output.outputFile.length() > 0) {
                "Component AOT output does not exist or is empty: " + output.outputFile.absolutePath
            }
            val actualDigest = FileDigest.sha256Hex(output.outputFile)
            require(actualDigest.equals(output.artifact.sha256, ignoreCase = true)) {
                "Component AOT output SHA-256 mismatch for " + output.outputFile.absolutePath +
                    ": expected " + output.artifact.sha256 + ", actual " + actualDigest + "."
            }
            output.artifact.copy(url = relativeChild(directory, output.outputFile))
        }
        val record = ComponentAotBuildRecord(
            rawComponent = rawComponent,
            inputComponentSha256 = result.inputComponentSha256,
            wasmtimeVersion = result.wasmtimeVersion,
            engineOptions = result.engineOptions,
            artifacts = artifacts,
        )
        outputFile.writeText(json.encodeToString(record))
        return record
    }

    fun read(inputFile: File): ComponentAotBuildRecord {
        require(inputFile.isFile) { "Component AOT build result does not exist: " + inputFile.absolutePath }
        return json.decodeFromString(inputFile.readText())
    }

    /** Copies verified native artifacts into a flat package directory. */
    fun materializeArtifacts(record: ComponentAotBuildRecord, sourceDirectory: File, destinationDirectory: File): List<WasmlineArtifact> {
        check(destinationDirectory.exists() || destinationDirectory.mkdirs()) {
            "Unable to create Component package directory: " + destinationDirectory.absolutePath
        }
        val resolved = record.resolveArtifacts(sourceDirectory)
        val duplicateNames = resolved.groupBy { it.file.name.lowercase() }
            .filterValues { it.size > 1 }
            .keys
        require(duplicateNames.isEmpty()) {
            "Component AOT artifacts have duplicate package filenames: " + duplicateNames.sorted().joinToString()
        }
        return resolved.map { resolvedArtifact ->
            val destination = File(destinationDirectory, resolvedArtifact.file.name)
            if (resolvedArtifact.file.canonicalFile != destination.canonicalFile) {
                Files.copy(
                    resolvedArtifact.file.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            val actualDigest = FileDigest.sha256Hex(destination)
            require(actualDigest.equals(resolvedArtifact.artifact.sha256, ignoreCase = true)) {
                "Materialized Component AOT artifact SHA-256 mismatch: " + destination.absolutePath
            }
            resolvedArtifact.artifact.copy(url = destination.name)
        }
    }

    private fun relativeChild(directory: File, file: File): String {
        val root = directory.toPath().toAbsolutePath().normalize()
        val child = file.toPath().toAbsolutePath().normalize()
        require(child.startsWith(root)) {
            "Component AOT output is outside its result directory: " + file.absolutePath
        }
        return root.relativize(child).toString().replace(File.separatorChar, '/')
    }
}
