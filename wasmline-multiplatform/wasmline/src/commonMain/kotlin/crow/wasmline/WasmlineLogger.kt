package crow.wasmline

/**
 * Platform-agnostic logging interface for Wasmline SDK.
 *
 * Implement this interface to route Wasmline internal logs to your
 * application's logging framework (e.g., Logcat, os_log, SLF4J).
 *
 * Set via [WasmlineLog.logger]. When `null`, all log calls are skipped.
 */
interface WasmlineLogger {
    fun info(message: String)
    fun debug(message: String)
    fun warn(message: String)
    fun error(message: String)
}

/**
 * Singleton holder for the active [WasmlineLogger].
 *
 * Set before loading modules:
 * ```kotlin
 * WasmlineLog.logger = MyLogger()
 * ```
 * When `logger` is `null`, all SDK-internal log calls are skipped.
 */
object WasmlineLog {
    var logger: WasmlineLogger? = null
}
