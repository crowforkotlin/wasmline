# Changelog

## Unreleased

- Tool and AOT compiler downloads stop forwarding authorization after a redirect
  changes the scheme, host, or port. External tool timeouts also bound output
  draining and process cleanup.
- Native Core Wasm start callbacks can create an independent raw session without
  holding the session registry locks. Releasing a pending session prevents its
  publication, and start import failures retain their error code and details.
- JNI converts artifact paths and export names from UTF-16 to standard UTF-8,
  including supplementary Unicode characters.
- Generated Host WIT bindings represent `char` as a validated `UInt` Unicode
  scalar instead of Kotlin `Char`. Regenerate bindings and migrate `Char` inputs
  to scalar values. Resource-containing variant decoders receive their instance.
- Signed browser loads associate verification with the current byte registration.
  Concurrent in-process file-cache writes reserve capacity across cache instances,
  and Ktor artifact downloads deliver bytes to the sink before buffering the full
  response.
- Publish canonical `wasmline.wit` and its SHA-256 checksum as GitHub Release
  assets for non-Kotlin Wasmline Service Component plugins.
- `wasmlineServerDeploy` binds to `127.0.0.1` by default and serves only files
  directly inside the package directory; `..`, absolute paths, and symlink
  escapes return 404, index entries are HTML-escaped, and downloads stream
  instead of loading the whole file. Set `server.host` to expose the server.
- `WasmlineLoadOptions.cache` is authoritative: a supplied cache receives every
  artifact, and `WasmlineNoOpCache` no longer writes into the built-in cache
  directory. Artifact downloads are staged in a transient file that is deleted
  after the runtime opens it.
- Manifest format version 2 signs the envelope `publicKeyId`, so changing the
  key identifier now invalidates the signature. The loader accepts only the
  current format version, so version 1 packages must be re-signed.
- Remote manifest URLs keep their query string for both the manifest and the
  content-addressed artifact request.
- The browser `env` bridge implements `bridge_inbound_get_size`, clamps
  `bridge_inbound_copy_params` to the available bytes, and returns
  `REENTRANT_CALL` for a nested invocation instead of overwriting call state.
- The Kotlin typed-invocation decoder rejects a collection count that cannot fit
  the remaining payload, matching the native decoder.
- `generate-key-pair --save` writes the private key with owner-only permissions
  and stops printing it; the Gradle key task writes both keys under
  `build/wasmline/keys` and reports only the public key.

## 1.0.0-beta1

Initial Wasmline beta release.

Public APIs and Gradle configuration may change before 1.0.0.

- Core Wasm Service and Raw Export invocation, typed Component invocation,
  Component host imports, and the Wasmline Service Component contract.
- Native Cranelift and Pulley engines, including Pulley execution on iOS.
- Signed manifests, content-addressed artifact storage, local and remote
  loading, and SHA-256 verification before loading.
- Native AOT generation selection backed by a packaged compatibility catalog.
- Kotlin compiler and Gradle plugins, CLI packaging tools, and Kotlin, Rust,
  C, and C++ Component examples.
- Browser Core Wasm Service and Raw Export execution with raw WebAssembly.
- One Maven version across runtime, loader, network adapters, plugins, CLI,
  and engines, with strict version constraints in the BOM.
- Maven coordinates use `io.github.crowforkotlin`; the Gradle plugin ID is
  `io.github.crowforkotlin.wasmline`.
- Alpha and beta Maven versions use `-alphaN` and `-betaN`; Git release tags
  retain the Wasmtime version code.
