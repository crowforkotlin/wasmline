package crow.wasmline.plugin.core.packaging

import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that package directory publication preserves the last successful output.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
class WasmlineDirectoryTransactionTest {
    @Test
    fun commitsACompleteReplacement() = withTransactionDirectory { root ->
        val destination = File(root, "package").apply { mkdirs() }
        File(destination, "old.txt").writeText("old")

        WasmlineDirectoryTransaction.create(destination).use { transaction ->
            File(transaction.stagingDirectory, "new.txt").writeText("new")
            transaction.commit()
        }

        assertFalse(File(destination, "old.txt").exists())
        assertEquals("new", File(destination, "new.txt").readText())
    }

    @Test
    fun failedCommitRestoresThePreviousDirectory() = withTransactionDirectory { root ->
        val destination = File(root, "package").apply { mkdirs() }
        File(destination, "stable.txt").writeText("stable")

        WasmlineDirectoryTransaction.create(destination).use { transaction ->
            assertTrue(transaction.stagingDirectory.deleteRecursively())
            assertFailsWith<IllegalStateException> { transaction.commit() }
        }

        assertEquals("stable", File(destination, "stable.txt").readText())
        assertTrue(root.listFiles().orEmpty().none { it.name.contains(".backup-") })
    }

    @Test
    fun commitsDirectoryAndPublicationFileTogether() = withTransactionDirectory { root ->
        val destination = File(root, "package").apply { mkdirs() }
        File(destination, "old.txt").writeText("old")
        val archive = File(root, "package.zip").apply { writeText("old archive") }
        val stagedArchive = File(root, "package-staged.zip").apply { writeText("new archive") }

        WasmlineDirectoryTransaction.create(destination).use { transaction ->
            File(transaction.stagingDirectory, "new.txt").writeText("new")
            transaction.commitWithFile(stagedArchive, archive)
        }

        assertFalse(File(destination, "old.txt").exists())
        assertEquals("new", File(destination, "new.txt").readText())
        assertEquals("new archive", archive.readText())
        assertFalse(stagedArchive.exists())
        assertTrue(root.listFiles().orEmpty().none { it.name.contains(".backup-") })
    }

    @Test
    fun failedPublicationFileCommitRestoresBothPreviousOutputs() = withTransactionDirectory { root ->
        val destination = File(root, "package").apply { mkdirs() }
        File(destination, "stable.txt").writeText("stable")
        val archive = File(root, "package.zip").apply { writeText("stable archive") }
        val stagedArchive = File(root, "package-staged.zip").apply { writeText("new archive") }

        WasmlineDirectoryTransaction.create(destination) { source, _ ->
            if (source == stagedArchive.absoluteFile) throw IOException("forced publication file failure")
        }.use { transaction ->
            File(transaction.stagingDirectory, "new.txt").writeText("new")
            assertFailsWith<IllegalStateException> { transaction.commitWithFile(stagedArchive, archive) }
        }

        assertEquals("stable", File(destination, "stable.txt").readText())
        assertFalse(File(destination, "new.txt").exists())
        assertEquals("stable archive", archive.readText())
        assertEquals("new archive", stagedArchive.readText())
        assertTrue(root.listFiles().orEmpty().none { it.name.contains(".backup-") })
    }
}

private inline fun withTransactionDirectory(block: (File) -> Unit) {
    val directory = createTempDirectory("wasmline-directory-transaction-test").toFile()
    try {
        block(directory)
    } finally {
        directory.deleteRecursively()
    }
}
