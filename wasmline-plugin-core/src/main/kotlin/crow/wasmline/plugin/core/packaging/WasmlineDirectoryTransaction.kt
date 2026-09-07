package crow.wasmline.plugin.core.packaging

import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Builds a directory beside its destination and replaces the last successful output transactionally.
 *
 * Date: 2026-08-28
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
class WasmlineDirectoryTransaction private constructor(
    val destination: File,
    val stagingDirectory: File,
    private val beforeMove: ((File, File) -> Unit)?,
) : Closeable {
    private var committed = false
    private val backupsPendingCleanup = mutableListOf<File>()

    /** Commits the complete staging directory while restoring the previous output on failure. */
    fun commit(): File = commitInternal(null, null)

    /** Commits the staging directory and one related file as a single recoverable publication. */
    fun commitWithFile(stagedFile: File, destinationFile: File): File = commitInternal(stagedFile, destinationFile)

    private fun commitInternal(stagedFile: File?, destinationFile: File?): File {
        check(!committed) { "Directory transaction has already been committed." }
        require((stagedFile == null) == (destinationFile == null)) {
            "A staged file and destination file must be provided together."
        }
        val stagedPublicationFile = stagedFile?.absoluteFile
        val publicationFile = destinationFile?.absoluteFile
        if (stagedPublicationFile != null && publicationFile != null) {
            require(stagedPublicationFile.isFile) {
                "Staged publication file does not exist: ${stagedPublicationFile.absolutePath}"
            }
            require(stagedPublicationFile != publicationFile) {
                "Staged and destination publication files must differ."
            }
            require(!publicationFile.isDirectory) {
                "Publication file destination is a directory: ${publicationFile.absolutePath}"
            }
            val publicationParent = requireNotNull(publicationFile.parentFile)
            check(publicationParent.isDirectory || publicationParent.mkdirs()) {
                "Unable to create publication file parent directory: ${publicationParent.absolutePath}"
            }
        }

        val directoryBackup = backupPath(destination)
        val fileBackup = publicationFile?.let(::backupPath)
        try {
            if (destination.exists()) {
                movePath(destination, directoryBackup, atomic = false)
            }
            movePath(stagingDirectory, destination, atomic = !directoryBackup.exists())
            if (stagedPublicationFile != null && publicationFile != null && fileBackup != null) {
                if (publicationFile.exists()) {
                    movePath(publicationFile, fileBackup, atomic = false)
                }
                movePath(stagedPublicationFile, publicationFile, atomic = !fileBackup.exists())
            }
        } catch (error: Throwable) {
            recoverOutput(publicationFile, fileBackup, stagedPublicationFile, error)
            recoverOutput(destination, directoryBackup, stagingDirectory, error)
            throw IllegalStateException("Unable to commit package outputs for '${destination.absolutePath}'.", error)
        }

        committed = true
        cleanupCommittedBackup(directoryBackup)
        fileBackup?.let(::cleanupCommittedBackup)
        return destination
    }

    override fun close() {
        if (!committed) {
            if (stagingDirectory.exists()) stagingDirectory.deleteRecursively()
            return
        }
        backupsPendingCleanup.toList().forEach(::cleanupCommittedBackup)
    }

    /**
     * Creates package directory transactions and performs same-filesystem moves.
     *
     * Date: 2026-08-28
     * Author: crowforkotlin
     */
    companion object {
        /** Creates a same-filesystem staging directory beside the destination. */
        fun create(destination: File): WasmlineDirectoryTransaction = create(destination, null)

        /** Creates a transaction with a move hook used by deterministic filesystem failure tests. */
        internal fun create(destination: File, beforeMove: ((File, File) -> Unit)?): WasmlineDirectoryTransaction {
            val absoluteDestination = destination.absoluteFile
            val parent = requireNotNull(absoluteDestination.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "Unable to create package parent directory: ${parent.absolutePath}" }
            val staging = Files.createTempDirectory(parent.toPath(), ".${absoluteDestination.name}.staging-").toFile()
            return WasmlineDirectoryTransaction(absoluteDestination, staging, beforeMove)
        }
    }

    private fun cleanupCommittedBackup(backup: File) {
        if (!backup.exists() || backup.deleteRecursively()) {
            backupsPendingCleanup.remove(backup)
            return
        }
        if (backup !in backupsPendingCleanup) backupsPendingCleanup += backup
        backup.walkBottomUp().forEach(File::deleteOnExit)
    }

    private fun recoverOutput(current: File?, backup: File?, staged: File?, originalError: Throwable) {
        if (current == null || backup == null || staged == null) return
        try {
            if (backup.exists()) {
                if (current.exists()) {
                    check(current.deleteRecursively()) { "Unable to remove failed output: ${current.absolutePath}" }
                }
                movePath(backup, current, atomic = false)
            } else if (!staged.exists() && current.exists()) {
                check(current.deleteRecursively()) { "Unable to remove partially committed output: ${current.absolutePath}" }
            }
        } catch (recoveryError: Throwable) {
            originalError.addSuppressed(recoveryError)
        }
    }

    private fun movePath(source: File, destination: File, atomic: Boolean) {
        beforeMove?.invoke(source, destination)
        if (atomic) {
            runCatching {
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }.onSuccess { return }
        }
        Files.move(source.toPath(), destination.toPath())
    }

    private fun backupPath(output: File): File = File(
        requireNotNull(output.parentFile),
        ".${output.name}.backup-${System.nanoTime()}",
    )
}
