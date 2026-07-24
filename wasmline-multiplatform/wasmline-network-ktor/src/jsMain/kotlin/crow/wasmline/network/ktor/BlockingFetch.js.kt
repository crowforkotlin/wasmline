package crow.wasmline.network.ktor

import crow.wasmline.network.WasmlineHttpResponse
import io.ktor.client.HttpClient

internal actual fun blockingKtorFetch(client: HttpClient, url: String): WasmlineHttpResponse = browserBlockingKtorFetch(client, url)
