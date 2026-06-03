package crow.wasmline.loader

/**
 * Describes where to load a Wasmline module from.
 *
 * - [LocalArtifactPath]: Direct path to a precompiled artifact (`.cwasm` / `.pwasm` / `.wasm`).
 * - [LocalManifestPath]: Path to a local `manifest.wlm` file that references one or more artifacts.
 * - [RemoteManifestUrl]: URL to a remote manifest for download and verification.
 */
sealed interface WasmlineSource {
    data class LocalArtifactPath(val path: String) : WasmlineSource
    data class LocalManifestPath(val path: String) : WasmlineSource
    data class RemoteManifestUrl(val url: String) : WasmlineSource
}
