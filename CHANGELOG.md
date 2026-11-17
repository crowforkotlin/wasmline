# Changelog

## Unreleased

No unreleased changes.

## 1.0.0-beta2

Covers every change merged or pushed after `1.0.0-beta1`.

Public APIs, Gradle configuration, and the manifest format may still change
before 1.0.0.

### Build. Publication

- Publish an empty javadoc jar for every Maven coordinate. The Dokka site is
  published once at the documentation site instead of being copied into all 40
  platform variants, whose pre-compressed 10.9 MB `woff2` font does not compress
  further. Release size drops from 577.77 MB to 95.23 MB.
- #60 Declare the native test library as an optional file collection and declare
  it for `nativeAotJvmTest`.

### Release. Assets

- Publish the canonical `wasmline.wit` and its SHA-256 checksum as GitHub
  Release assets, so non-Kotlin `WASMLINE_SERVICE` Component plugins can target
  the contract. A missing WIT now fails the release job.

### Server. Artifact deployment

- #22 Reject path traversal outside the serve directory: `..`, absolute paths,
  directories, and escaping symlinks return 404, and downloads stream with
  `LocalFileContent` instead of loading the whole file.
- #23 Escape artifact names in the index page and percent-encode their links.
- #32 Serve nested `artifacts/sha256/{prefix}/{digest}.{ext}` paths and list them
  in the index page, so `wasmlineServerDeploy` can deliver the package it
  assembled. The server now binds to `127.0.0.1` by default instead of
  `0.0.0.0`; set `server.host` to expose it.

### Loader. Artifact cache

- #24 Honor the configured cache for artifacts: `WasmlineLoadOptions.cache` is
  authoritative and the only storage consulted, and `WasmlineNoOpCache` stages a
  transient file instead of writing into the built-in cache directory.
- #33 Enforce `maxCacheBytes` for fresh and oversized entries by reserving space
  before a write and diverting an artifact that does not fit into a transient
  file.
- #47 Reserve cache bytes across concurrent downloads in one process sharing a
  filesystem and canonical directory. Cross-process enforcement is not claimed.
- #46 Invalidate web cache verification when the registered bytes change, so a
  signed load is checked against the current immutable registration.
- #55 Execute the verified bytes of a local artifact: the file is copied into
  transient storage while it is hashed, so a replacement between digest check
  and load can no longer run unverified code.

### Loader. Manifest

- #26 Preserve the `?query` of a remote manifest URL for both the manifest
  request and the content-addressed artifact request.
- #27 Bind `publicKeyId` into the manifest signature as format version 2, so
  relabeling a signed envelope invalidates it. The loader accepts only the
  current format, so version 1 packages must be re-signed.
- #35 Remove the key-less `signingMessage` overload.
- #37 Verify the declared AOT compile-profile schema and reject a profile whose
  `compileProfileSchemaVersion` the loader cannot interpret.

### Core. Session lifecycle

- #41 Avoid the creation lock during start callbacks, so a host import that
  creates another Component instance no longer blocks on a mutex the calling
  thread already owns.
- #51 Run Component and Service start callbacks outside the creation lock and
  track pending sessions, so a nested create or release returns instead of
  deadlocking. Cancelled sessions are never published.
- #49 Preserve structured import failures during start: the error code and
  details survive instead of collapsing into a generic failure.

### Native. Kotlin/Native bridge

- #53 Give each instance its own native recursive mutex, so two independent
  Component instances no longer block each other across Workers.
- #54 Pass UTF-8 byte lengths instead of UTF-16 code unit counts for export
  names, component export names, and inbound actions.

### Native. JNI

- #42 Encode supplementary names and artifact paths as standard UTF-8. Non-ASCII
  export names previously failed with `EXPORT_NOT_FOUND`.

### Host. WIT bindings

- #43 Represent generated `char` values as a validated `UInt` Unicode scalar and
  preserve the full code point. See Breaking changes.
- #44 Pass the instance to resource-containing variant decoders.
- #57 Resolve types that a WIT interface declares with `use`.
- #58 Keep nested `option` values distinct instead of collapsing levels.
- #59 Avoid the instance name in generated signatures when a WIT parameter
  shadows it.

### Toolchain. Downloads

- #40 Stop forwarding tool and AOT download credentials once a redirect leaves
  the original scheme, host, or port.
- #45 Bound process cleanup after a timeout and keep output draining bounded.
- #56 Serialize same-process tool downloads before acquiring the cross-process
  file lock, which previously failed with `OverlappingFileLockException`.
- #52 Scope GitHub tokens to the original origin in the Python release scripts,
  matching the Gradle plugin.

### Web. Invocation bridge

- #29 Match the native `env` bridge surface by implementing
  `bridge_inbound_get_size` and clamping `bridge_inbound_copy_params` to the
  available bytes.
- #30 Reject reentrant calls on a web module with `REENTRANT_CALL` instead of
  overwriting the in-flight call state.

### Invocation. Typed frames

- #28 Reject collection counts that exceed the remaining payload, matching the
  native decoder.
- #36 Pin the typed result frame to `WASMLINE_NATIVE_BRIDGE_ABI_VERSION` and
  document that its layout is part of the ABI.

### Network. Ktor

- #48 Stream `fetchTo` before the response is fully buffered.

### CLI. Signing keys

- #25 Write the signing key as `0600` and stop printing the private key. The
  Gradle key task writes both keys under `build/wasmline/keys` and reports only
  the public key.
- #34 Apply `0600` when `generate-key-pair` rewrites an existing key file, not
  only to a newly created one.

### Documentation

- #38 Remove the ECDSA-P256 signature claim from the design notes.

### Breaking changes

- #43 Generated Host WIT bindings represent `char` as `UInt` instead of Kotlin
  `Char`. Regenerate the bindings and migrate `Char` inputs, for example `'A'`
  becomes `'A'.code.toUInt()`. Surrogates and values above `0x10FFFF` are
  rejected. The raw Component `CharValue` representation, the WIT source, and
  the native ABI are unchanged.
- #27 Signed packages in manifest format version 1 are rejected and must be
  re-signed.
- #35 The key-less `signingMessage` overload is removed.
- User-visible behavior: `wasmlineServerDeploy` binds to `127.0.0.1` by default,
  and `WasmlineLoadOptions.cache` no longer falls back to the built-in cache
  directory when a cache is supplied.

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
