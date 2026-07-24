@file:OptIn(ExperimentalSerializationApi::class)

package crow.wasmline.loader.internal

import crow.wasmline.WasmlineLoadState
import crow.wasmline.loader.WasmlineSource
import crow.wasmline.loader.WasmlineSourceResolution
import crow.wasmline.loader.model.SignedManifestEnvelope
import crow.wasmline.loader.model.WasmlineArtifact
import crow.wasmline.loader.model.WasmlineArtifactType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import okio.ByteString.Companion.toByteString

internal object WasmlineLocalPackageResolution {
    fun resolve(source: WasmlineSource.LocalManifestPath): WasmlineSourceResolution {
        val manifestPath = source.path
        if (!hostPathExists(manifestPath)) {
            return failure("Local package manifest not found: ${source.path}")
        }

        val envelope = readEnvelope(manifestPath) ?: return failure(
            "Failed to parse local package manifest '${source.path}'.",
        )
        val artifact = selectArtifact(envelope.manifest.artifacts) ?: return failure(
            "No compatible artifact found in local package '${source.path}' for host ${describe(currentHostArtifactTarget)}.",
        )
        val artifactPath = resolveArtifactPath(manifestPath, artifact.url)
        if (!hostPathExists(artifactPath)) {
            return failure(
                "Artifact '${artifact.url}' referenced by local package '${source.path}' was not found at '$artifactPath'.",
            )
        }

        val actualSha256 = sha256HexOrNull(artifactPath) ?: return failure(
            "Failed to read artifact '${artifact.url}' referenced by local package '${source.path}'.",
        )
        if (!actualSha256.equals(artifact.sha256, ignoreCase = true)) {
            return failure(
                "Artifact '${artifact.url}' referenced by local package '${source.path}' failed sha256 verification. " +
                    "Expected ${artifact.sha256}, actual $actualSha256.",
            )
        }

        return WasmlineSourceResolution.ContinueWith(
            WasmlineSource.LocalArtifactPath(path = artifactPath),
        )
    }

    internal fun selectArtifact(
        artifacts: List<WasmlineArtifact>,
        target: WasmlineHostArtifactTarget = currentHostArtifactTarget,
    ): WasmlineArtifact? = artifacts
        .mapNotNull { artifact ->
            artifact.selectionScoreFor(target)?.let { score -> score to artifact }
        }
        .maxByOrNull { (score, _) -> score }
        ?.second

    private fun readEnvelope(manifestPath: String): SignedManifestEnvelope? {
        val bytes = readHostFileBytes(manifestPath) ?: return null
        return try {
            ProtoBuf.decodeFromByteArray(SignedManifestEnvelope.serializer(), bytes)
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256HexOrNull(path: String): String? = readHostFileBytes(path)?.toByteString()?.sha256()?.hex()

    private fun resolveArtifactPath(manifestPath: String, artifactUrl: String): String = resolveHostArtifactPath(manifestPath, artifactUrl)

    private fun WasmlineArtifact.selectionScoreFor(target: WasmlineHostArtifactTarget): Int? = when (type) {
        WasmlineArtifactType.WASM -> wasmSelectionScore(target)
        WasmlineArtifactType.CWASM -> cwasmSelectionScore(target)
        WasmlineArtifactType.PWASM -> pwasmSelectionScore(target)
    }

    private fun WasmlineArtifact.wasmSelectionScore(target: WasmlineHostArtifactTarget): Int? {
        if (normalizeOs(target.os) != "browser") {
            return null
        }
        val artifactOs = normalizeOs(targetOs)
        if (artifactOs != null && artifactOs != "browser") {
            return null
        }
        val artifactCpu = normalizeCpu(targetCpu)
        if (artifactCpu != null && artifactCpu != target.cpu) {
            return null
        }
        return 500
    }

    private fun WasmlineArtifact.cwasmSelectionScore(target: WasmlineHostArtifactTarget): Int? {
        val artifactOs = normalizeOs(targetOs) ?: return null
        val artifactCpu = normalizeCpu(targetCpu) ?: return null
        if (artifactOs != target.os || artifactCpu != target.cpu || is64Bit != target.is64Bit) {
            return null
        }
        return 300
    }

    private fun WasmlineArtifact.pwasmSelectionScore(target: WasmlineHostArtifactTarget): Int? {
        if (is64Bit != target.is64Bit) {
            return null
        }
        val artifactOs = normalizeOs(targetOs)
        if (artifactOs != null && artifactOs != target.os) {
            return null
        }
        val artifactCpu = normalizeCpu(targetCpu)
        return when {
            artifactCpu == null -> 200
            artifactCpu == target.cpu -> 190
            artifactCpu.startsWith("pulley") && matchesPulleyBitness(artifactCpu, target.is64Bit) -> 180
            else -> null
        }
    }

    private fun matchesPulleyBitness(cpu: String, is64Bit: Boolean): Boolean = when {
        cpu.endsWith("64") -> is64Bit
        cpu.endsWith("32") -> !is64Bit
        else -> true
    }

    private fun describe(target: WasmlineHostArtifactTarget): String {
        val bitness = if (target.is64Bit) "64-bit" else "32-bit"
        return "${target.os}/${target.cpu} ($bitness)"
    }

    private fun normalizeOs(value: String?): String? = when (value?.lowercase()) {
        null -> null
        "mac", "macos", "darwin", "osx" -> "macos"
        "win", "windows" -> "windows"
        "linux" -> "linux"
        "android" -> "android"
        "ios" -> "ios"
        "web", "browser" -> "browser"
        else -> value.lowercase()
    }

    private fun normalizeCpu(value: String?): String? = when (value?.lowercase()) {
        null -> null
        "amd64", "x86_64" -> "x86_64"
        "arm64", "aarch64" -> "aarch64"
        "wasm", "wasm32", "wasmjs", "browser" -> "wasmjs"
        else -> value.lowercase()
    }

    private fun failure(cause: String): WasmlineSourceResolution.Complete = WasmlineSourceResolution.Complete(
        WasmlineLoadState.Failure(
            code = WasmlineLoadState.CODE_FAILURE,
            cause = cause,
        ),
    )
}
