# Changelog

## Unreleased

No unreleased changes.

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
