package crow.wasmline.loader.internal

import android.system.Os
import okio.Path

/** Applies owner-only permissions to a transient directory on the Android file system. */
internal actual fun hardenTransientDirectory(directory: Path) {
    runCatching { Os.chmod(directory.toString(), 0x1C0) }
}
