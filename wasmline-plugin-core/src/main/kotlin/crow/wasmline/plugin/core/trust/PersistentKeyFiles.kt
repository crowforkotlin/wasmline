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
 * the private key is created with mode 0600 on POSIX hosts. Hosts without POSIX
 * file attributes fall back to the platform default mode.
 *
 * Date: 2026-11-11
 * Author: crowforkotlin
 */
@InternalWasmlineToolingApi
public object PersistentKeyFiles {

    /** Writes [privateKeyHex] to [file] and restricts it to the owner when possible. */
    public fun writePrivateKey(file: File, privateKeyHex: String) {
        val path = file.toPath()
        val posix = supportsPosixAttributes(path)
        if (!posix) {
            file.writeText(privateKeyHex)
            return
        }
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PRIVATE_KEY_PERMISSIONS))
        Files.writeString(path, privateKeyHex)
    }

    private fun supportsPosixAttributes(path: Path): Boolean = !Files.exists(path) && runCatching {
        path.fileSystem.supportedFileAttributeViews().contains("posix")
    }.getOrDefault(false)

    private val PRIVATE_KEY_PERMISSIONS: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")
}
