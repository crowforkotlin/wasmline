package crow.wasmline.network.ktor

import crow.wasmline.WasmlineHttpResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.runBlocking

internal actual fun blockingKtorFetch(client: HttpClient, url: String): WasmlineHttpResponse {
    return runBlocking {
        val response = client.get(url)
        WasmlineHttpResponse(
            statusCode = response.status.value,
            bytes = response.bodyAsBytes(),
        )
    }
}
