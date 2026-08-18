package crow.wasmline.loader.internal

import okio.FileSystem

/** Returns the blocking host file system, or `null` on browser targets. */
internal expect fun defaultHostFileSystem(): FileSystem?
