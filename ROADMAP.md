# Wasmline Roadmap

This document outlines the planned and completed features for the Wasmline project.

> **Note**: When releasing version 1.0.0, all implemented features must be updated in this file
> with `[x]` to indicate completion status.

---

## Core Runtime

- [x] Wasmtime C-API integration (v45.0.3)
- [x] Dual-path execution model (Native + Web)
- [x] Session-based memory isolation
- [x] Engine singleton lifecycle management
- [x] AOT compilation support (`.cwasm` artifacts)
- [x] Pulley portable bytecode support (`.pwasm` artifacts)
- [x] Module cache with keyed storage
- [ ] Module hot-reload without host restart
- [ ] Concurrent multi-plugin execution within a single session
- [ ] Configurable resource limits (memory, CPU ticks) per session

## Platform Support

- [x] Android (arm64-v8a) — JNI bridge
- [x] iOS (arm64) — Kotlin/Native C Interop
- [x] macOS (arm64) — JNI bridge
- [x] Linux (x86_64) — JNI bridge
- [x] Windows (x86_64) — JNI bridge
- [x] Web — Kotlin/JS via browser WebAssembly API
- [x] Web — Kotlin/WasmJS via browser WebAssembly API
- [ ] Android (x86_64 emulator) — full CI validation
- [ ] Web — SharedArrayBuffer-based async execution
- [ ] Web — Service Worker off-thread plugin execution

## Compiler Plugin (IR Transformation)

- [x] Service contract discovery and validation
- [x] Bridge class synthesis (`*_WasmlineBridge`)
- [x] `link<T>()` call-site rewriting
- [x] `bind(impl)` call-site rewriting
- [x] SHA-256 action identifier derivation
- [x] Diagnostic error reporting for contract violations
- [x] IR box test infrastructure
- [ ] Support for overloaded methods via parameter-type-disambiguated action identifiers
- [ ] Support for `suspend` functions in service contracts
- [ ] Support for generic type parameters on service contracts

## Service Contracts & Bridge Protocol

- [x] `WasmlineService` interface-based contract definition
- [x] Single-parameter method signature support
- [x] Serialization/deserialization bridge synthesis
- [x] Bidirectional host ↔ plugin invocation
- [x] Base64 payload encoding for Web targets
- [ ] Multi-parameter method signature support
- [ ] Return type `Result<T>` for typed error propagation
- [ ] Streaming / chunked payload transfer for large data
- [ ] Contract versioning and backward-compatible evolution

## CLI Toolchain

- [x] `download` — Wasmtime binary acquisition
- [x] `generate-key-pair` — Ed25519 key generation
- [x] `compile` — AOT and Pulley compilation
- [x] `manifest` — Signed manifest generation (Protobuf + Ed25519)
- [x] `build` — Full pipeline orchestration
- [ ] Incremental compilation — skip unchanged modules
- [ ] Multi-target parallel compilation
- [ ] Plugin dependency resolution and bundling

## Security & Manifest

- [x] Ed25519 cryptographic signing
- [x] Protobuf-based manifest format (`.wlm`)
- [x] Manifest parsing and verification in `wasmline-loader`
- [ ] ECDSA-P256 signature algorithm support
- [ ] Manifest-based permission declaration and enforcement
- [ ] Plugin sandboxing policy configuration
- [ ] Certificate chain validation for third-party plugins

## Build System & Gradle Integration

- [x] `wasmline-gradle-plugin` — consumer project integration
- [x] `wasmline-build-logic` — shared convention plugins
- [x] Multi-platform KMP target configuration
- [x] Android NDK / CMake integration
- [x] Zig 0.15.1 JNI shared library compilation
- [ ] Gradle configuration cache full compatibility
- [ ] Published Gradle plugin portal distribution
- [ ] Kotlin Multiplatform project template generator

## Networking

- [x] Ktor-based HTTP client adapter (`wasmline-network-ktor`)
- [x] OkHttp-based HTTP client adapter (`wasmline-network-okhttp`)
- [ ] WASI preview2 HTTP proxy integration
- [ ] gRPC-over-Wasm bridge for plugin service discovery

## Documentation & Ecosystem

- [x] Sample applications (Android, Desktop, Compose Multiplatform, Web)
- [x] Multi-language README (English / Chinese)
- [x] Architecture documentation with mind maps
- [x] Documentation site (Next.js + Fumadocs)
- [ ] API reference documentation (Dokka)
- [ ] Plugin authoring guide
- [ ] Migration guide between major versions
- [ ] Community plugin registry

---

## Versioning Policy

| Phase   | Version  | Focus                                                  |
|---------|----------|--------------------------------------------------------|
| Alpha   | 0.x.x   | Core runtime stability, platform coverage, API design   |
| Beta    | 0.9.x   | Feature completeness, performance optimization, testing |
| Stable  | 1.0.0   | Production-ready release with full documentation        |

---

*Last updated: 2025-06-25*
