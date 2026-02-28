package crow.mordecai.wasmline.cli.extensions

import kotlinx.serialization.json.Json

internal val baseJson = Json {
    prettyPrint = true
    encodeDefaults = true
}