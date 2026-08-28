package crow.wasmline.plugin.core.aot

import crow.wasmline.WasmlineArtifactFormat
import crow.wasmline.WasmlineEngineKind
import crow.wasmline.loader.model.WasmlineArtifactTarget
import crow.wasmline.loader.model.WasmlineRuntimeContract
import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Records one complete Core or Component AOT compatibility build.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@Serializable
@InternalWasmlineToolingApi
data class WasmlineAotBuildRecord(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val inputFile: String,
    val inputSha256: String,
    val runtimeContract: WasmlineRuntimeContract,
    val resolvedProfiles: List<AotCompatibilityProfileSpec>,
    val requestedTargets: List<String>,
    val compiledOutputs: List<WasmlineCompiledArtifact>,
    val compilerProvenance: List<WasmlineAotCompilerProvenance>,
    val compileOptions: WasmlineAotCompileOptions,
    val artifactTargets: List<WasmlineArtifactTarget>,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported AOT build record schema $schemaVersion." }
        require(inputFile.isNotBlank()) { "AOT build input file must not be blank." }
        require(inputSha256.matches(SHA_256)) { "AOT build input SHA-256 must be lowercase hexadecimal." }
        require(requestedTargets.isNotEmpty()) { "AOT build record must contain requested targets." }
        require(compiledOutputs.isNotEmpty()) { "AOT build record must contain compiled outputs." }
        require(artifactTargets.isNotEmpty()) { "AOT build record must contain artifact targets." }
    }

    /**
     * Defines the current build record schema and content digest syntax.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        private val SHA_256: Regex = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Records one physical output produced by a matrix build unit.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@Serializable
@InternalWasmlineToolingApi
data class WasmlineCompiledArtifact(
    val requestedTarget: String,
    val normalizedTarget: String,
    val format: WasmlineArtifactFormat,
    val artifactBackend: WasmlineEngineKind? = null,
    val aotCompatibilityProfileId: String? = null,
    val operatingSystem: String? = null,
    val architecture: String,
    val pointerWidth: Int,
    val cpuFeatureProfile: String? = null,
    val sha256: String,
    val sizeBytes: Long,
    val contentRelativePath: String,
) {
    init {
        require(requestedTarget.isNotBlank()) { "Compiled artifact requestedTarget must not be blank." }
        require(normalizedTarget.isNotBlank()) { "Compiled artifact normalizedTarget must not be blank." }
        require(pointerWidth == 32 || pointerWidth == 64) { "Compiled artifact pointerWidth must be 32 or 64." }
        require(sizeBytes > 0) { "Compiled artifact sizeBytes must be positive." }
    }
}

/**
 * Records the immutable compiler asset used for one compatibility profile.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@Serializable
@InternalWasmlineToolingApi
data class WasmlineAotCompilerProvenance(
    val profileId: String,
    val artifactBackend: WasmlineEngineKind,
    val wasmtimeVersion: String,
    val wasmtimeDistributionVersion: String,
    val buildHost: String,
    val compilerArchiveSha256: String,
    val compilerExecutableSha256: String,
)

/**
 * Reads and writes deterministic AOT build records shared by every build adapter.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
object WasmlineAotBuildRecords {
    const val FILE_NAME: String = "aot-build-record.json"

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /** Writes a build record after creating its parent directory. */
    fun write(record: WasmlineAotBuildRecord, outputFile: File): File {
        outputFile.parentFile?.let { parent -> check(parent.isDirectory || parent.mkdirs()) }
        outputFile.writeText(json.encodeToString(record))
        return outputFile
    }

    /** Reads one complete build record. */
    fun read(inputFile: File): WasmlineAotBuildRecord {
        require(inputFile.isFile) { "AOT build record does not exist: ${inputFile.absolutePath}" }
        return json.decodeFromString(inputFile.readText())
    }

    /** Verifies and copies every referenced content object into another package directory. */
    fun materializeArtifacts(record: WasmlineAotBuildRecord, sourcePackageDirectory: File, destinationPackageDirectory: File) {
        val sourceStore = WasmlineContentAddressedStore(sourcePackageDirectory)
        val destinationStore = WasmlineContentAddressedStore(destinationPackageDirectory)
        record.compiledOutputs
            .distinctBy(WasmlineCompiledArtifact::contentRelativePath)
            .forEach { output ->
                val source = sourceStore.resolve(output.contentRelativePath)
                require(source.isFile && source.length() == output.sizeBytes) {
                    "AOT content object is missing or has the wrong size: ${source.absolutePath}"
                }
                require(crow.wasmline.plugin.core.toolchain.FileDigest.sha256Hex(source) == output.sha256) {
                    "AOT content object failed SHA-256 verification: ${source.absolutePath}"
                }
                val stored = destinationStore.put(source, output.format)
                require(stored.relativePath == output.contentRelativePath) {
                    "AOT content object resolved to an unexpected destination path."
                }
            }
    }
}
