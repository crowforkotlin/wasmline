# Wasmline Technical Mind Map

## Three Independent Runtime Axes

Every artifact is described by three independent properties. Do not infer one property from another.

| Axis | Values | Selects |
| --- | --- | --- |
| Physical format | `RAW_WASM`, `CWASM`, `PWASM` | How bytes are loaded |
| Execution model | `CORE_WASM`, `COMPONENT_MODEL` | Wasmtime Core or Component runtime |
| Invocation protocol | `WASMLINE_SERVICE`, `RAW_EXPORT`, `COMPONENT_EXPORT` | How calls and values cross the boundary |

Valid runtime combinations:

| Execution model | Protocol | Boundary |
| --- | --- | --- |
| `CORE_WASM` | `WASMLINE_SERVICE` | Generated service bridge, action string, serialized byte payload, and Core response frame |
| `CORE_WASM` | `RAW_EXPORT` | Explicit Core numeric export ABI |
| `COMPONENT_MODEL` | `WASMLINE_SERVICE` | Fixed `wasmline:service@1.0.0` WIT envelope |
| `COMPONENT_MODEL` | `COMPONENT_EXPORT` | Typed Component values, host imports, instances, and resources |

`RAW_EXPORT` is invalid with `COMPONENT_MODEL`, and `COMPONENT_EXPORT` is invalid with `CORE_WASM`.

## Artifact Model

- Raw `.wasm` is a source/build format.
  - The browser executes it as `CORE_WASM + WASMLINE_SERVICE` or
    `CORE_WASM + RAW_EXPORT`.
  - Native loading rejects raw Core and Component artifacts.
- `.cwasm` is platform-specific Cranelift AOT output.
- `.pwasm` is Wasmtime Pulley bytecode, normally `pulley32` or `pulley64`.
- Both `.cwasm` and `.pwasm` can contain a Core module or a Component. The descriptor's execution model selects the native runtime path.
- Cranelift runtime distributions support matching `.cwasm` and Pulley fallback. Pulley distributions accept `.pwasm` only.
- iOS is Pulley-only and selects `pulley64` `.pwasm`.
- Native compatibility includes physical format, CPU, OS, bitness, backend capability, and exact Wasmtime compiler version.

## Repository Modules

- `wasmline-core`
  - C/C++ native runtime compiled through Zig 0.16.0
  - `include/wasmline/`: stable native API headers
  - `include/wasmline/internal/`: native implementation headers; not a compatibility surface
  - `Api.cpp`: short public native facade
  - `NativeRuntime.cpp`: engine, artifact, and process lifecycle coordination
  - `ServiceSessionRegistry.cpp`, `RawSessionRegistry.cpp`, `ComponentSessionRegistry.cpp`: session lookup, ownership, and artifact detachment
  - `Engine.cpp`: Cranelift/Pulley engine lifecycle
  - `Module.cpp`: deserialize-only Core artifact cache
  - `Component.cpp`: deserialize-only Component artifact cache
  - `Session.cpp`: Wasmline Service Core calls
  - `RawModuleSession.cpp`: raw Core exports
  - `ComponentSession.cpp`: Component exports, service envelope, host imports, instances, and resources
- `wasmline`
  - Public Kotlin runtime types and platform implementations
  - Generated service bridge contracts and serialization SPI
  - Host-side raw and Component invocation APIs
  - Core guest router and Component Service guest initialization
  - Browser Core Wasmline runtime
- `wasmline-loader`
  - Local and remote `.wlm` packages
  - Signature verification and trusted-key policy
  - Artifact selection and descriptor construction
- `wasmline-engine-cranelift`, `wasmline-engine-pulley`
  - Packaged native runtime distributions and JVM platform variants
- `wasmline-kotlin-plugin`
  - Service contract validation, IR bridge generation, `link`/`bind` rewriting, and guest initialization hooks
- `wasmline-plugin-core`
  - Shared tool downloads, Core/Component compilation, Component AOT, manifest signing, packaging, and WIT host bindings
- `wasmline-gradle-plugin`
  - Consumer DSL and Gradle task graph built on the compiler plugin and plugin core
- `wasmline-cli`
  - Command-line adapters for the same build and packaging services
- `wasmline-network-ktor`, `wasmline-network-okhttp`
  - Loader network implementations
- `wasmline-android`
  - Android CMake/JNI integration
- `wasmline-plugin-test`
  - End-to-end plugin build and native invocation verification

## Native Host Flow

```text
WasmlineLoader
  -> verified manifest or caller-trusted descriptor
  -> select CWASM/PWASM for host runtime
  -> internal runtime bridge
  -> Wasmline platform implementation (JNI or Kotlin/Native C interop)
  -> wasmline-core Api facade
  -> NativeRuntime lifecycle coordinator
  -> Module or Component cache
  -> ServiceSessionRegistry, RawSessionRegistry, or ComponentSessionRegistry
  -> Session, RawModuleSession, or ComponentSession
  -> Wasmtime
  -> WasmlineCallResult
```

Cached session calls do not acquire the global lifecycle mutex. Each registry
retains the selected session under its own shared mutex, and each session owns
cloned Wasmtime engine and artifact handles. Artifact release and shutdown take
the lifecycle write lock, detach matching sessions first, release the cached
artifact and process engine, then destroy detached sessions after the global
lock is released.

Native loading is deserialize-only. `wasmtime_module_new` and `wasmtime_component_new` are intentionally rejected in the runtime path; compilation belongs to plugin-core, the Gradle plugin, or the CLI.

The selected physical format controls engine mode:

- `CWASM` initializes or selects the Cranelift engine.
- `PWASM` initializes or selects the Pulley engine.
- Selecting the current engine is idempotent.
- Switching engines is allowed only when no artifacts remain loaded. A conflicting warm-up or artifact load fails without releasing existing artifacts; only explicit runtime shutdown invalidates all live handles.

## Browser Host Flow

```text
WasmlineWeb.prefetch(url) or WasmlineWeb.registerBytes(key, bytes)
  -> Fetch API or caller-provided bytes
  -> WebWasmArtifacts byte cache
  -> WasmlineLoader.load(...) [suspend]
  -> WebAssembly.Module and WebAssembly.Instance
  -> Service path: __wasmline_wasi_init / __wasmline_wasi_entry / response frame
  -> Raw path: CoreWasmModule / CoreWasmSession / direct exports
```

Browser limits:

- raw `.wasm` only
- `CORE_WASM + WASMLINE_SERVICE` or `CORE_WASM + RAW_EXPORT`
- no native runtime identity
- no concurrent loading
- no Component instances or resources
- raw imports are synchronous and must be registered before instantiation
- `i64` crosses the JavaScript boundary as `BigInt`; threads/shared memory are not promised
- raw memory access is bounds-checked and refreshed after `memory.grow`

The shared `webMain` layer contains no platform interop. `jsMain` supplies typed JS externals; `wasmJsMain` supplies `JsAny` and constant `js()` helpers.

The Service/WASI lifecycle and the Raw Export lifecycle are intentionally
separate. Raw modules do not assume `__wasmline_wasi_init`,
`__wasmline_wasi_entry`, or `env.bridge_*`; they register only the imports
their module declares.

## Core Wasmline Service Flow

Host to plugin:

```text
module.link<Service>().method(...)
  -> generated Service_WasmlineBridge
  -> selected serialization factory
  -> action = fully-qualified-contract + "#" + method-name
  -> Wasmline.callResult(action, payload)
  -> Session or WebWasmPlugin
  -> WasmlineRouter
  -> bound implementation
  -> WLMF response frame
  -> WasmlineCallResult
  -> typed return value
```

Plugin to host uses the same generated bridge shape through the outbound host dispatcher. Overloaded service methods are forbidden, so the current action identifier remains unique within a contract.

Normal call failures are values:

- `ACTION_NOT_BOUND`
- invalid payload or response
- trap or transport failure
- handler failure

`throwOnFailure()` is an explicit adapter; the result API does not use exceptions for normal call failures.

## Component Model Flow

### Build

```text
WIT and guest source
  -> wit-bindgen where required
  -> Core Wasm guest
  -> wasm-tools component embed/new
  -> raw Component Wasm
  -> compile-capable Wasmtime CLI
  -> Component CWASM/PWASM targets
  -> signed manifest and package
```

The raw Component is an intermediate build result, not a native runtime artifact. Component AOT compilation uses the fork's `cranelift-min` CLI by default, accepts compile-capable full CLIs as fallbacks, verifies the exact Wasmtime version and `compile` capability, and rejects iOS CWASM targets in favor of `pulley64`.

### Runtime

- `WASMLINE_SERVICE`
  - Uses the fixed `wasmline:service@1.0.0` host/plugin interfaces.
  - Keeps the selected Wasmline serialization bytes inside `list<u8>` payloads.
  - Supports Component-to-host service callbacks.
- `COMPONENT_EXPORT`
  - Uses `WasmlineComponentValue` for typed parameters and results.
  - Supports generated host bindings, imported host functions, explicit instances, and owned/borrowed resources.
  - Does not use Core linear-memory bridge imports or the `WLMF` response frame.

## Kotlin IR Pipeline

```text
WasmlineCompilerPluginRegistrar
  -> WasmlineCommandLineProcessor options
  -> WasmlineIrGenerationExtension
       1. discover WasmlineService interfaces
       2. validate contracts and functions
       3. generate *_WasmlineBridge classes
       4. rewrite link() and bind() calls
       5a. generate Core WASI exports, or
       5b. wire the Component Service init hook
```

Current validation rejects:

- generic service interfaces or generic service methods
- service properties
- overloaded method names
- non-public or suspend methods
- extension receivers
- default or vararg parameters
- service contracts used as parameter or return types

Multiple regular parameters are supported. Generated bridges build the required serializer array and parameter descriptor for those methods.

The plugin is an IR transformer, not a source generator. `link()` and `bind()` deliberately fail at runtime when the compiler plugin did not rewrite them.

## Verification Surfaces

- Native runtime: `wasmline/src/jvmTest/`, `iosTest/`, and `wasmline-core` integration paths
- Browser runtime: `wasmline/src/webTest/`
- Loader: common, JVM, Web, and Kotlin/Native tests under `wasmline-loader/src/`
- Plugin build pipeline: `wasmline-plugin-core/src/test/` and `wasmline-plugin-test/`
- IR plugin: `wasmline-kotlin-plugin/testData/box/`, generated `test-gen/`, and FIR/IR snapshots

Generated runners, snapshots, build outputs, and platform assets must not be edited manually.
