package crow.wasmline.network.ktor

import crow.wasmline.network.WasmlineHttpResponse
import crow.wasmline.network.WasmlineNetworkClient
import io.ktor.client.HttpClient

/**
 * [WasmlineNetworkClient] implementation backed by Ktor HttpClient.
 *
 * Ktor selects the appropriate engine per platform:
 * - JVM: CIO engine
 * - Android: OkHttp engine
 * - iOS: Darwin engine
 * - Web (JS/WasmJs): not supported (throws [UnsupportedOperationException])
 *
 * **Web platform note:** Synchronous HTTP fetch is not supported on web.
 * Browser callers should provide a custom [crow.wasmline.loader.WasmlineRemotePackageResolver]
 * with async logic instead.
 *
 * @param client Optional pre-configured [HttpClient] for custom configuration.
 */
class KtorNetworkClient(private val client: HttpClient = HttpClient()) : WasmlineNetworkClient {

    override fun fetch(url: String): WasmlineHttpResponse = blockingKtorFetch(client, url)
}

/**
 * Factory function for creating a [KtorNetworkClient].
 */
fun ktorNetworkClient(client: HttpClient = HttpClient()): WasmlineNetworkClient = KtorNetworkClient(client)

/**
 * Platform-specific blocking HTTP GET implementation.
 * - JVM/Android/iOS: bridges suspend Ktor call to blocking via runBlocking.
 * - Web: throws UnsupportedOperationException.
 */
internal expect fun blockingKtorFetch(client: HttpClient, url: String): WasmlineHttpResponse
