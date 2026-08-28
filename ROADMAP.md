# Wasmline Roadmap

## Completed Features

### Core Runtime
- [x] Wasmtime C-API integration (v48.0.1)
- [x] Dual-path execution: Native and Web
- [x] Session-based memory isolation
- [x] Engine singleton lifecycle
- [x] AOT compilation (`.cwasm`)
- [x] Pulley portable bytecode (`.pwasm`)
- [x] Module keyed cache

### Platform Support
- [x] Android (arm64-v8a, arm-eabi, x86_64, x86)
- [x] iOS (arm64)
- [x] macOS (arm64)
- [x] Linux (x86_64)
- [x] Windows (x86_64)
- [x] Web - Kotlin/JS via browser WebAssembly API
- [x] Web - Kotlin/WasmJS via browser WebAssembly API

### Compiler Plugin (IR Transformation)
- [x] Service contract discovery (`WasmlineService` interface)
- [x] Bridge class synthesis (`*_WasmlineBridge`)
- [x] `link<T>()` call rewriting
- [x] `bind(impl)` call rewriting
- [x] SHA-256 action identifiers
- [x] Diagnostic error reporting
- [x] IR box test infrastructure
- [x] Contract validation (interface-only, no suspend, single parameter)

### Service Contracts
- [x] Interface-based contracts
- [x] Single-parameter methods
- [x] Multi-parameter methods
- [x] Automatic serialization (Protobuf, raw bytes)
- [x] Bidirectional host ↔ plugin invocation
- [x] Base64 encoding for Web targets

### CLI Toolchain
- [x] `download` - Wasmtime binary acquisition
- [x] `generate-key-pair` - Ed25519 key generation
- [x] `compile` - AOT and Pulley compilation
- [x] `manifest` - Signed manifest generation
- [x] `build` - Full pipeline orchestration

### Security & Manifest
- [x] Ed25519 digital signatures
- [x] Protobuf manifest format (`.wlm`)
- [x] Manifest verification on load

### Gradle Integration
- [x] `wasmline-gradle-plugin`
- [x] `wasmline-build-logic` convention plugins
- [x] KMP multiplatform configuration
- [x] Android NDK / CMake integration
- [x] Zig 0.15.1 JNI compilation

### Network Clients
- [x] Ktor HTTP client adapter
- [x] OkHttp HTTP client adapter

### Documentation & Samples
- [x] Multi-platform sample apps
- [x] English and Chinese documentation
- [x] Architecture diagrams and design docs
- [x] Next.js + Fumadocs site

---

## Planned Features

### Runtime Enhancements
- [ ] Hot-reload: replace loaded module without host restart
- [ ] Concurrent multi-plugin execution
- [ ] Resource limits (memory, CPU ticks) per session
- [ ] Streaming/chunked data transfer for large payloads

### Compiler Plugin Enhancements
- [ ] Method overload support (type-disambiguated action IDs)
- [ ] Suspend function support (async/await)
- [ ] Generic type parameters in contracts
- [ ] Default parameters in method signatures
- [ ] Property access support
- [ ] Improved diagnostic messages with quick fixes

### Build System Improvements
- [ ] Incremental compilation (skip unchanged modules)
- [ ] Parallel multi-target compilation
- [ ] Plugin dependency resolution and bundling
- [ ] Gradle build cache compatibility
- [ ] Maven central publishing

### Security & Sandboxing
- [ ] Manifest-based permission declarations
- [ ] Runtime permission enforcement
- [ ] Plugin sandboxing policies
- [ ] Certificate chain validation (third-party plugins)

### Platform Coverage
- [ ] Android x86_64 full CI validation
- [ ] Web SharedArrayBuffer async execution
- [ ] Web Service Worker off-thread execution
- [ ] Additional architectures (RISC-V, ARMv7)

### Network & Protocol
- [ ] WASI Preview 2 HTTP integration
- [ ] gRPC-over-Wasm bridge for service discovery
- [ ] Plugin marketplace discovery protocol

### Developer Experience
- [ ] API reference (Dokka)
- [ ] Plugin authoring guide
- [ ] Migration guides between major versions
- [ ] Community plugin registry
- [ ] IntelliJ/IDE plugin for hot reload debugging

### Testing & Quality
- [ ] Fuzzing tests for serialization
- [ ] Performance benchmarks suite
- [ ] Cross-version compatibility tests
- [ ] Memory leak detection tools

---

## Version Plan

| Phase   | Version  | Focus                              |
|---------|----------|------------------------------------|
| Alpha   | 0.x.x    | Core stability, platform coverage  |
| Beta    | 0.9.x    | Feature completeness, performance  |
| Stable  | 1.0.0    | Production-ready, complete docs    |

---

*Last updated: 2026-07-27*
