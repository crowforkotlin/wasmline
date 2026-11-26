package crow.wasmline.loader.internal

import kotlinx.coroutines.test.runTest
import okio.FileSystem
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class TransientDirectoryTest {
    @Test
    fun `transient directories are unique and owner-only`() {
        val first = assertNotNull(WasmlineArtifactStaging.transientDirectory(FileSystem.SYSTEM))
        val second = assertNotNull(WasmlineArtifactStaging.transientDirectory(FileSystem.SYSTEM))
        try {
            assertNotEquals(first, second)
            val permissions = Files.getPosixFilePermissions(Paths.get(first.toString()))
            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
                permissions,
            )
        } finally {
            FileSystem.SYSTEM.deleteRecursively(first)
            FileSystem.SYSTEM.deleteRecursively(second)
        }
    }

    @Test
    fun `registered transient directory is removed with its file`() = runTest {
        val directory = assertNotNull(WasmlineArtifactStaging.transientDirectory(FileSystem.SYSTEM))
        val file = directory / "artifact.cwasm"
        FileSystem.SYSTEM.write(file) { write(byteArrayOf(1, 2, 3)) }
        WasmlineTransientArtifacts.register(file.toString(), directory.toString())
        WasmlineTransientArtifacts.deleteIfTransient(file.toString())
        assertFalse(FileSystem.SYSTEM.exists(file))
        assertFalse(FileSystem.SYSTEM.exists(directory))
    }
}
