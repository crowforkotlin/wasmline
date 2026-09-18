package crow.wasmline.samples.minimal.library.security

import crow.wasmline.generated.WasmlineHostTrust
import crow.wasmline.loader.WasmlineLoadOptions
import crow.wasmline.loader.network.WasmlineNetworkClient

// Generated from the wasmline.trust Gradle configuration.
internal fun sampleLoadOptions(networkClient: WasmlineNetworkClient? = null) = WasmlineLoadOptions(
    networkClient = networkClient,
    trustedKeys = WasmlineHostTrust,
)
