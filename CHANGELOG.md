# Changelog

## Unreleased

Initial Wasmline runtime and build-tool release.

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
