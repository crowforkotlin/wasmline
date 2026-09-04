# Wasmline Roadmap

Status as of 2026-09-04. Wasmline 1.0.0 is in the release-hardening phase:
the core runtime, plugin toolchain, Component Model path, package format, and
documentation system are implemented. The remaining items below are explicit
scope decisions or release-quality work.

## Completed Features

### Core Runtime

- [x] Wasmtime C-API integration (Wasmline Wasmtime 48.0.1)
- [x] Core Wasm and Component Model execution paths
- [x] Native and browser runtime implementations
- [x] Session-isolated execution state and lifecycle management
- [x] Engine warm-up, shutdown, artifact release, and runtime identity checks
- [x] Module and Component artifact caches
- [x] Cranelift AOT artifacts (`.cwasm`)
- [x] Pulley portable bytecode (`.pwasm`) and native fallback selection
- [x] Core Wasm `RAW_EXPORT` invocation with scalar values, imports, and memory
- [x] Result-based invocation failures and structured error codes
- [x] Component typed values, host imports, instances, and resource ownership

### Platform and CI Coverage

- [x] Android native asset targets: arm64-v8a, armeabi-v7a, x86, and x86_64
- [x] iOS device and simulator arm64 Pulley targets
- [x] macOS arm64/x86_64 native asset targets
- [x] Linux arm64/x86_64 native asset targets
- [x] Windows x86_64 native asset target
- [x] Kotlin/JS and Kotlin/WasmJS Core Wasm browser runtime
- [x] Kotlin/Wasm WASI runtime tests
- [x] JVM, native AOT, iOS simulator, browser, Node.js, and plugin CI jobs

The target matrix is broader than the currently complete CI matrix. The
remaining platform-validation gaps are listed under Release Hardening.

### Kotlin Compiler Plugin

- [x] `WasmlineService` contract discovery
- [x] `*_WasmlineBridge` class synthesis
- [x] `link<T>()` and `bind(impl)` rewriting
- [x] SHA-256 action identifiers
- [x] Compiler diagnostics for invalid contracts
- [x] IR box test infrastructure
- [x] Single- and multi-parameter service methods
- [x] Core WASI entry generation and Component Service initialization hooks
- [x] Explicit validation of unsupported declarations

Current service-contract boundaries are documented and intentionally reject
overloads, suspend functions, generic contracts or methods, default and
vararg parameters, properties, extension receivers, and non-public methods.

### Service Contracts and Component Model

- [x] Interface-based service contracts
- [x] Protobuf and raw-byte serialization profiles
- [x] Bidirectional host-to-plugin and plugin-to-host service calls
- [x] Fixed `wasmline:service@1.0.0` Component Service WIT world
- [x] Component Model typed export invocation
- [x] Generated Kotlin host WIT bindings
- [x] Owned and borrowed Component resource handling
- [x] Kotlin, Rust, C, and C++ Component fixtures
- [x] Browser support for Core Service and Core Raw Export paths
- [x] Explicit native-only boundary for typed Component instances and resources

### Package, Loader, and Security Model

- [x] Signed Protobuf manifest format (`manifest.wlm`)
- [x] Ed25519 signing and trusted-key verification
- [x] Canonical manifest validation and bounded untrusted-input decoding
- [x] SHA-256 content-addressed artifact layout
- [x] Local and remote package loading
- [x] Streaming artifact downloads with atomic cache publication
- [x] Runtime-aware selection of `.cwasm` and `.pwasm` variants
- [x] Catalog-driven, multi-profile AOT compatibility selection
- [x] Locked Wasmtime compiler/tool downloads with digest verification

### CLI and Gradle Toolchain

- [x] Wasmtime download and platform-target inspection
- [x] Ed25519 key-pair generation
- [x] Core and Component AOT compilation
- [x] Manifest signing and deterministic package creation
- [x] CLI command for the complete build flow
- [x] WIT binding generation, Componentize, validation, and inspection commands
- [x] `wasmline-gradle-plugin` user configuration DSL and task dependencies
- [x] Gradle Component tool and host-binding tasks
- [x] Parallel multi-target AOT compilation with bounded concurrency
- [x] Cacheable Gradle tasks for AOT, bindings, and native fixtures
- [x] Local package server deployment task
- [x] Repository version, AOT catalog, toolchain, lint, and doctor tooling

### Network, Documentation, and Release Automation

- [x] Ktor HTTP client adapter
- [x] OkHttp HTTP client adapter
- [x] English and Chinese documentation
- [x] Architecture, runtime, testing, CLI, Gradle, and Component guides
- [x] Next.js + Fumadocs static documentation site
- [x] Dokka API reference generation and documentation-site deployment workflow
- [x] Maven Central publication configuration and release workflow
- [x] GitHub release artifact and release-notes workflow

## Partially Complete and Release Hardening

| Area | Current state | Remaining work |
| --- | --- | --- |
| Native concurrency | Multiple native sessions and concurrent Core Service calls have coverage; individual Raw/Component sessions still serialize or reject overlapping operations | Define and test the supported concurrent invocation contract across all protocols and platforms |
| Incremental builds and cache | Compiler/tool caches and several `@CacheableTask` tasks exist | Validate full Gradle build-cache behavior and add compilation-unit-level reuse if required |
| Large-payload transfer | Remote artifact download is streaming | Service invocation still uses `ByteArray` or WIT `list<u8>`; invocation streaming is not implemented |
| Platform support | Android armv7 and x86_64 targets exist; native asset builds cover more targets than runtime CI | Run Android x86_64/device validation and document each target's build output, runtime path, and CI status |
| Performance | Invocation benchmark code exists | Establish a repeatable benchmark suite, baselines, and regression thresholds |
| Compatibility | AOT catalog, profile selection, and compatibility reports exist | Add runtime tests that execute the same package across supported Wasmtime profiles |
| Maven release | Publication configuration and CI workflow exist | Execute and verify the first public release; keep the release tag and Maven release paired |

## Remaining Features

### Runtime Enhancements

- [ ] Hot reload: replace a loaded module atomically without restarting the host
- [ ] Fully specified concurrent multi-plugin loading and invocation semantics
- [ ] Per-session resource limits, including memory, fuel/CPU budget, and timeout policy
- [ ] Streaming or chunked service invocation for large payloads

### Compiler Plugin Enhancements

- [ ] Method overload support with type-disambiguated action identifiers
- [ ] Suspend function support with an explicit asynchronous host protocol
- [ ] Generic type parameters in service contracts
- [ ] Default parameters in service methods
- [ ] Property access support
- [ ] IDE-oriented diagnostics and quick fixes

### Build System Enhancements

- [ ] Compilation-unit-level incremental compilation
- [ ] Plugin dependency resolution and dependency bundling
- [ ] Full Gradle build-cache validation for all Wasmline tasks

### Security and Sandboxing

- [ ] Manifest-based permission declarations
- [ ] Runtime permission enforcement
- [ ] Configurable plugin sandbox policies
- [ ] Certificate-chain validation for third-party plugin signing

### Platform Coverage

- [ ] Complete Android x86_64 CI/device validation
- [ ] Web SharedArrayBuffer-based asynchronous execution
- [ ] Web Service Worker off-thread execution
- [ ] RISC-V support

### Network and Protocol

- [ ] WASI Preview 2 HTTP integration
- [ ] gRPC-over-Wasm service discovery bridge
- [ ] Plugin marketplace discovery protocol

### Developer Experience

- [ ] Migration guides between major versions
- [ ] Community plugin registry
- [ ] IntelliJ/IDE plugin for hot-reload debugging

### Testing and Quality

- [ ] Serialization fuzzing tests
- [ ] Production performance benchmark suite
- [ ] Cross-version runtime compatibility test matrix
- [ ] Memory-leak detection tooling

## 1.0.0 Release Checklist

- [ ] Run and review all required CI jobs, including native AOT and iOS gates
- [ ] Publish and verify the Maven artifacts and matching release tag
- [ ] Publish and verify the bilingual documentation site and Dokka API reference
- [ ] Document the supported platforms, artifact types, execution models, and invocation protocols, including validation status for each item
- [ ] Document the unsupported compiler-plugin features and native/browser boundaries
- [ ] Publish release notes and the migration/support policy for the first stable release

## Version Plan

| Phase | Version | Focus |
| --- | --- | --- |
| Current | 1.0.0 | Release hardening, support boundaries, CI, publication, and documentation verification |
| Post-1.0 | 1.x | Optional runtime, compiler, security, protocol, and platform expansions |

---

*Last updated: 2026-09-04*
