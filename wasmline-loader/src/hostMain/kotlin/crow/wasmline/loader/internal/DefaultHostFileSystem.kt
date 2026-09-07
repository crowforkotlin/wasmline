package crow.wasmline.loader.internal

import okio.FileSystem

/**
 * Returns the blocking host filesystem, or `null` on browser targets.
 *
 * Author: crowforkotlin
 * Date: 2026-08-19
 */
internal expect fun defaultHostFileSystem(): FileSystem?
