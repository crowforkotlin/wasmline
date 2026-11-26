package crow.wasmline.loader.internal

import okio.Path
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

/** Applies owner-only permissions to a transient directory on POSIX file systems. */
internal actual fun hardenTransientDirectory(directory: Path) {
    runCatching {
        Files.setPosixFilePermissions(Paths.get(directory.toString()), PosixFilePermissions.fromString("rwx------"))
    }
}
