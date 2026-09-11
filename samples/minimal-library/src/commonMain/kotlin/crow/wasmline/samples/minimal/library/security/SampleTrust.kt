package crow.wasmline.samples.minimal.library.security

import crow.wasmline.loader.WasmlineLoadOptions
import crow.wasmline.loader.WasmlineTrustedKeySet
import crow.wasmline.loader.network.WasmlineNetworkClient

// Matches the public demonstration key in each minimal example.
internal fun sampleLoadOptions(networkClient: WasmlineNetworkClient? = null) = WasmlineLoadOptions(
    networkClient = networkClient,
    trustedKeys = WasmlineTrustedKeySet.Builder()
        .addHex(
            algorithm = "Ed25519",
            keyId = null,
            publicKeyHex = "5a778289bee0c57b05a1c48c8ef312da6ce8e4e4f13fc1a2e8e5aa4cde7ae0db",
        )
        .build(),
)
