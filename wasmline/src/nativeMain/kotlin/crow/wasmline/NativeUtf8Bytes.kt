package crow.wasmline

/**
 * Returns the standard UTF-8 byte length of this string.
 *
 * Kotlin/Native converts a `String` to `const char*` as UTF-8, and the native
 * C API interprets a `size_t` name length as UTF-8 bytes. `String.length` counts
 * UTF-16 code units, so any call that passes both a name and a length must use
 * this value for the length.
 *
 * Date: 2026-11-16
 * Author: crowforkotlin
 */
internal fun String.utf8ByteLength(): ULong = encodeToByteArray().size.toULong()
