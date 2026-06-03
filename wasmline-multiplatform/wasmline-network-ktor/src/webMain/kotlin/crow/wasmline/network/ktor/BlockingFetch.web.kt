package crow.wasmline.network.ktor

import crow.wasmline.WasmlineHttpResponse
import io.ktor.client.HttpClient

internal fun browserBlockingKtorFetch(client: HttpClient, url: String): WasmlineHttpResponse {
    throw UnsupportedOperationException(
        "Synchronous network fetch is not supported on web platforms. " +
            "Provide a custom WasmlineRemotePackageResolver with async logic instead.",
    )
}
