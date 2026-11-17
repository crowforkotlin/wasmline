# Changelog

## 1.0.0-beta2

Published 2026-11-17. Covers every change merged or pushed after `1.0.0-beta1`.

Public APIs and Gradle configuration may change before 1.0.0.

### Added

- Publish the canonical `wasmline.wit` and its SHA-256 checksum as GitHub Release
  assets, so non-Kotlin `WASMLINE_SERVICE` Component plugins can target the
  contract without resolving the Gradle plugin. A missing WIT now fails the
  release job instead of shipping an incomplete asset set.

### Changed

- Published javadoc jars are empty for every Maven coordinate. Each of the 40
  platform-variant coordinates previously carried its own full copy of the
  Dokka site, and a pre-compressed 10.9 MB `.woff2` font inside it does not
  compress further, so documentation accounted for 482 MB of a 578 MB release.
  The generated site is published once at the documentation site instead. The
  empty jar still satisfies the Maven Central requirement, matching what other
  Kotlin Multiplatform libraries publish.
- `wasmlineServerDeploy` now serves every file below the package directory,
  including nested `artifacts/sha256/{prefix}/{digest}.{ext}` objects, and lists
  them in the index page. Previously only files directly inside the directory
  were reachable, so a package assembled by the task could not be downloaded.
  Downloads stream with `LocalFileContent` instead of loading the whole file, and
  index entries are HTML-escaped with percent-encoded links.
- `wasmlineServerDeploy` binds to `127.0.0.1` by default instead of `0.0.0.0`.
  Set `server.host` to expose the server.
- `WasmlineLoadOptions.cache` is authoritative: a supplied cache receives every
  artifact and is the only storage consulted. `WasmlineNoOpCache` stages a
  transient file that the loader deletes after the runtime opens it, instead of
  writing into the built-in cache directory. Shared download, verification, and
  publication logic moved into `WasmlineArtifactStaging`.
- `WasmlineFileCache` treats `maxCacheBytes` as a hard bound: it reserves space
  before a write and diverts an artifact that does not fit into a transient
  file. Capacity is reserved across concurrent writers in one process sharing a
  filesystem and canonical directory; cross-process enforcement is not claimed.
- A locally resolved package artifact is copied into transient storage while it
  is hashed, so the runtime opens the exact bytes whose SHA-256 matched the
  signed manifest. Previously the verified path was reopened, which let a
  replacement between verification and load go unnoticed.
- Remote manifest URLs keep their `?query` for both the manifest request and the
  content-addressed artifact request.
- The loader rejects manifest AOT profiles whose `compileProfileSchemaVersion`
  it cannot interpret, and the manifest model documents that the profile ID is
  authoritative.
- The Kotlin/Native bridge passes UTF-8 byte lengths instead of UTF-16 code unit
  counts for export names and inbound actions, and JNI converts artifact paths
  and export names from UTF-16 to standard UTF-8, including supplementary
  characters. Non-ASCII export names previously failed with `EXPORT_NOT_FOUND`.
- Every Kotlin/Native `WasmlineRuntimeLock` owns its own native recursive mutex,
  so two independent Component instances no longer block each other across
  Workers.
- Tool, AOT compiler, and Python GitHub downloads stop forwarding authorization
  once a redirect leaves the original scheme, host, or port. External tool
  timeouts also bound output draining and process cleanup.
- Same-asset tool downloads are serialized inside one JVM before acquiring the
  cross-process file lock, which previously failed with
  `OverlappingFileLockException`.
- Ktor artifact downloads deliver bytes to the sink before buffering the full
  response.

### Fixed

- Component and Service guest start callbacks run outside the registry creation
  lock, and pending sessions are tracked, so a nested create or release returns
  instead of deadlocking. Session keys are reserved before instantiation,
  cancelled sessions are never published, and start import failures retain their
  error code and details.
- Signed browser loads associate verification with the current immutable byte
  registration instead of a stale one.
- Manifest format version 2 signs the envelope `publicKeyId`, so relabeling a
  signed envelope invalidates it. The loader accepts only the current format, so
  version 1 packages must be re-signed.
- The browser `env` bridge implements `bridge_inbound_get_size`, clamps
  `bridge_inbound_copy_params` to the available bytes, and answers a nested
  invocation with `REENTRANT_CALL` instead of overwriting call state.
- The Kotlin typed-invocation decoder rejects a collection count that cannot fit
  the remaining payload, matching the native decoder. Both codecs document that
  their frame layout is part of `WASMLINE_NATIVE_BRIDGE_ABI_VERSION`.
- The Host WIT generator resolves `use`-declared types, renames parameters that
  shadow the generated Component instance, and keeps nested `option` levels
  distinct.
- `PersistentKeyFiles.writePrivateKey` applies mode `0600` to an existing key
  file instead of only to a newly created one. `generate-key-pair --save` stops
  printing the private key, and the Gradle key task writes both keys under
  `build/wasmline/keys` and reports only the public key.

### Breaking

- Generated Host WIT bindings represent `char` as a validated `UInt` Unicode
  scalar instead of Kotlin `Char`. Regenerate the bindings and migrate `Char`
  inputs, for example `'A'` becomes `'A'.code.toUInt()`. Surrogates and values
  above `0x10FFFF` are rejected. The raw Component `CharValue` representation,
  WIT source, and native ABI are unchanged.
- Signed packages in manifest format version 1 are rejected and must be
  re-signed, because version 2 signs the envelope `publicKeyId`.
- The key-less `signingMessage` overload is removed.

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
