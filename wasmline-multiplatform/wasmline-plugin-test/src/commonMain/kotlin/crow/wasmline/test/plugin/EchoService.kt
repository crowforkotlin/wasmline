package crow.wasmline.test.plugin

import crow.wasmline.WasmlineService

/**
 * Echo service interface for testing string round-trip communication.
 *
 * Validates bidirectional data flow between host and WASM plugin.
 *
 * Date: 2026-07-30
 * Author: crowforkotlin
 */
interface EchoService : WasmlineService {
    fun echo(message: String): String
    fun echoWithPrefix(prefix: String, message: String): String
}

/**
 * Implementation of EchoService that returns messages unchanged or with prefix.
 *
 * Date: 2026-07-30
 * Author: crowforkotlin
 */
class EchoServiceImpl : EchoService {
    override fun echo(message: String): String = message
    override fun echoWithPrefix(prefix: String, message: String): String = "$prefix$message"
}
