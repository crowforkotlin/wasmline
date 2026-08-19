package crow.wasmline.loader.internal

import okio.FileSystem

/**
 * Returns the system filesystem for Kotlin/Native hosts.
 *
 * Author: crowforkotlin
 * Date: 2026-08-19
 */
internal actual fun defaultHostFileSystem(): FileSystem? = FileSystem.SYSTEM
