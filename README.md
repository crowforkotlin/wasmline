[中文文档](README_zh.md) | [English](README.md)

---

# WasmLine

A **Kotlin Multiplatform** plugin framework powered by [Wasmtime](https://wasmtime.dev/), enabling **WebAssembly (WASI)** plugin loading and execution across Android, iOS, Desktop (macOS / Windows / Linux), and Web.

Plugins can be developed in **any language** that compiles to WASI — Kotlin, Rust, C/C++, Go, AssemblyScript, and more.

## Platform Support

| Platform | Architecture | Runtime | Status |
|----------|-------------|---------|--------|
| Android  | arm64-v8a   | Wasmtime (JNI/Zig) | Supported |
| iOS      | arm64       | Wasmtime (C Interop) | Supported |
| macOS    | arm64       | Wasmtime (JNI/Zig) | Supported |
| Linux    | x86_64      | Wasmtime (JNI/Zig) | Supported |
| Windows  | x86_64      | Wasmtime (JNI/Zig) | Supported |
| Web      | wasm32-wasi | Kotlin/Wasi | Planned |

## Architecture

<table>
  <tr>
    <td align="center"><img src="docs/public/images/architecture.png" alt="Architecture"></td>
  </tr>
  <tr>
    <td align="center">WasmLine Architecture</td>
  </tr>
</table>

### Module Structure

```
wasmline-multiplatform/
├── wasmline/              # Core runtime — WASM module loading & execution
├── wasmline-loader/       # Crypto & manifest — Ed25519/ECDSA-P256 signing, manifest serialization
├── wasmline-cli/          # CLI toolchain — build, compile, manifest, download, key generation
├── wasmline-android/      # Android native bindings (CMake / JNI C++)
├── wasmline-sample/       # Sample applications (plugin, Android, Desktop, Multiplatform Compose)
└── wasmline-build-logic/  # Gradle convention plugins
```

**Dependency flow**: `wasmline-cli` → `wasmline-loader` → `wasmline` (core)

### Key Technologies

- **Kotlin Multiplatform** with custom source set hierarchy (`kotlin.mpp.applyDefaultHierarchyTemplate=false`)
- **Wasmtime 41.0.1** as the underlying WASM runtime, with platform-specific native bindings (JNI via Zig, C Interop for iOS)
- **kotlinx.serialization** for both JSON and Protobuf formats
- **Ed25519** digital signatures for mandatory manifest signing and verification
- **Clikt** for CLI command-line interface
- **AOT compilation** — `.wasm` → `.cwasm` (platform-specific) / `.pwasm` (Pulley portable bytecode)

## Getting Started

### Prerequisites

- **JDK 21** or later
- **Zig 0.15.1** (for native library builds only)

### Initialization

Initialize the necessary platform libraries:

```bash
sh ./scripts/init.sh
```

### Build

All Gradle commands run from the `wasmline-multiplatform/` directory:

```bash
# Build everything
./gradlew build

# Run loader tests (commonTest — ManifestTest, crypto, etc.)
./gradlew :wasmline-loader:jvmTest

# Run CLI tests
./gradlew :wasmline-cli:test

# Compile WASM plugin sample
./gradlew :wasmline-sample:plugin:compileProductionLibraryKotlinWasmWasiOptimize
```

### Native (Zig) Build

Outputs JNI native libraries for the host platform:

```bash
# From the wasmline/ directory
zig build --release=small -p src/jvmMain/resources          # release
zig build -p src/jvmMain/resources                           # debug
```

## CLI Toolchain

WasmLine provides a CLI for the full plugin build pipeline. All commands run via Gradle:

```bash
./gradlew :wasmline-cli:run --args="<command> [options]"
```

| Command | Description |
|---------|-------------|
| `download` | Download Wasmtime releases for target platforms |
| `generate-key-pair` | Generate Ed25519 key pairs for manifest signing |
| `compile` | Compile `.wasm` to platform-specific AOT artifacts (`.cwasm` / `.pwasm`) |
| `manifest` | Generate a signed manifest (`.wlm`) from compile output |
| `build` | Full pipeline: compile → manifest → zip packaging |

### Example: Full Build Pipeline

```bash
# 1. Download Wasmtime
./gradlew :wasmline-cli:run --args="download -v v41.0.1"

# 2. Generate signing keys
./gradlew :wasmline-cli:run --args="generate-key-pair --save"

# 3. Build plugin (compile → sign → package)
./gradlew :wasmline-cli:run --args="build -i plugin.wasm -wt build/wasmline/wasmtime/wasmtime-v41.0.1-aarch64-macos --key build/wasmline/keys/ed25519_private.key"
```

### Build Output

```
build/wasmline/
├── output/{name}-{version}/
│   ├── manifest.wlm                # Signed manifest (Protobuf)
│   ├── {name}-pulley64.pwasm       # Pulley portable bytecode
│   ├── {name}-aarch64-android.cwasm
│   ├── {name}-aarch64-macos.cwasm
│   ├── {name}-aarch64-ios.cwasm
│   ├── {name}-x86_64-linux.cwasm
│   ├── {name}-x86_64-windows.cwasm
│   └── debug/
│       ├── compile-result.json
│       └── manifest.json           # Human-readable manifest
├── dist/
│   └── {name}-{version}.zip        # Distributable package
└── keys/
    ├── ed25519_private.key
    └── ed25519_public.key
```

See [`wasmline-cli/`](wasmline-multiplatform/wasmline-cli/) for per-command documentation.

## Samples

Run all sample applications:

```bash
sh ./scripts/samples/run.sh
```

<table>
  <tr>
    <td align="center"><img src="docs/public/images/android_sample.png" alt="Android Sample"></td>
    <td align="center"><img src="docs/public/images/macos_sample.png" alt="macOS Sample"></td>
  </tr>
  <tr>
    <td align="center">Android</td>
    <td align="center">macOS (Native)</td>
  </tr>
</table>
<table>
  <tr>
    <td align="center"><img src="docs/public/images/compose_desktop_mac.png" alt="Compose Desktop"></td>
  </tr>
  <tr>
    <td align="center">Compose Desktop (macOS)</td>
  </tr>
</table>

## Kotlin/Wasi Compatibility

<table>
  <tr>
    <td align="center"><img src="docs/public/images/kotlin_support.png" alt="Kotlin Support"></td>
  </tr>
  <tr>
    <td align="center">Kotlin/Wasi Runtime Support Status</td>
  </tr>
</table>

WasmLine requires **Kotlin 2.3.20-Beta1** or later for reliable Kotlin/Wasi support. Earlier versions and other runtimes may throw errors or lack support for required Wasm features (GC, function references, exception handling).

## Resources

- [wasm-kotlin-exploration](https://github.com/crowforkotlin/wasm-kotlin-exploration) — Wasm + Kotlin research and test cases
- [Wasmtime on Android (JNI)](https://crowforkotlin.github.io/2025/11/27/Wasm/Android%E4%BD%BF%E7%94%A8JNI%E5%B5%8C%E5%85%A5Wasmtime/) — Embedding Wasmtime on Android via JNI
- [Wasm vs. Wasi Deep Dive](https://crowforkotlin.github.io/2025/11/25/Wasm/Wasm%E5%92%8CWasi%E5%8C%BA%E5%88%AB%E5%92%8C%E7%94%9F%E5%91%BD%E5%91%A8%E5%BA%86/) — Differences between Wasm and Wasi, and their lifecycles

## License

See [LICENSE](LICENSE) for details.