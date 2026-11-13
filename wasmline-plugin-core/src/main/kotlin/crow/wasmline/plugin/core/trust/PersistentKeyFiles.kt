package crow.wasmline.plugin.core.trust

import crow.wasmline.plugin.core.InternalWasmlineToolingApi
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Writes signing key files with owner-only permissions where the host supports them.
 *
 * A signing key readable by other local users defeats the manifest signature, so
 * the private key is written with mode 0600 on POSIX hosts, whether the file is
 * created or replaced. Hosts without POSIX file attributes fall back to the
 * platform default mode.
 *
 * Date: 2026-11-11
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
public object PersistentKeyFiles {

    /**
     * Writes [privateKeyHex] to [file] and restricts it to the owner when possible.
     *
     * An existing file keeps its inode: the content is replaced and the mode is
     * applied afterwards. A new file is created with mode 0600 directly, so the key
     * is never readable by other users, not even briefly.
     */
    public fun writePrivateKey(file: File, privateKeyHex: String) {
        val path = file.toPath()
        if (!supportsPosixAttributes(path)) {
            file.writeText(privateKeyHex)
            return
        }
        if (Files.notExists(path)) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PRIVATE_KEY_PERMISSIONS))
        }
        Files.writeString(path, privateKeyHex)
        Files.setPosixFilePermissions(path, PRIVATE_KEY_PERMISSIONS)
    }

    private fun supportsPosixAttributes(path: Path): Boolean = runCatching {
        path.fileSystem.supportedFileAttributeViews().contains("posix")
    }.getOrDefault(false)

    private val PRIVATE_KEY_PERMISSIONS: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")
}
