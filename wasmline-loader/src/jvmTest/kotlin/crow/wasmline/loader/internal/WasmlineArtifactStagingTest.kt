package crow.wasmline.loader.internal

import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertIs

class WasmlineArtifactStagingTest {
    @Test
    fun `copyVerified rejects a same-size digest mismatch`() {
        val root = Files.createTempDirectory("wasmline-staging-test").toFile()
        try {
            val source = root.resolve("source.cwasm").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val target = root.resolve("staged.cwasm").absolutePath.toPath()
            val wrongDigest = byteArrayOf(5, 6, 7, 8).toByteString().sha256().hex()
            val result = WasmlineArtifactStaging.copyVerified(
                sourcePath = source.absolutePath.toPath(),
                target = target,
                fileSystem = FileSystem.SYSTEM,
                expectedSha256 = wrongDigest,
                expectedSizeBytes = 4,
                maxArtifactBytes = 16,
            )
            assertIs<ArtifactFileResolution.HashMismatch>(result)
        } finally {
            root.deleteRecursively()
        }
    }
}
