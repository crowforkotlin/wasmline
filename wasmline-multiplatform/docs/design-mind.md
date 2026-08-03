# Wasmline — Technical Mind Map

## Core Concept & Data Boundaries

- Wasmline
  - Type: Kotlin Multiplatform WebAssembly plugin execution framework
  - Contract unit: Kotlin `interface` extending `WasmlineService` — defines the binary protocol boundary
  - Bridge unit: `*_WasmlineBridge` — one generated IR class per contract
  - Action identifier: SHA-256 of fully-qualified method signature — stable, hex-encoded string
  - Payload unit: `ByteArray` — serialized via `WasmlineSerializationFactory`
  - Linear memory boundary: Kotlin process memory vs. Wasm sandbox linear memory
  - Cross-boundary encoding (Web): Base64 string via `BrowserPayloadEncoding`
  - Artifact unit: raw `.wasm` (source) / `.cwasm` (AOT, platform-specific) / `.pwasm` (Pulley portable)
  - Distribution unit: `{name}-{version}.zip` — contains `.wlm` manifest + compiled artifact set
  - Execution isolation: per-`Session` linear memory region on native; browser sandbox on Web

---

## Module Dependency Topology

- wasmline-cli
  - depends-on: wasmline-loader
    - depends-on: wasmline (core runtime)
      - depends-on: wasmline-core (C/C++ — JNI / C Interop transport layer)
- wasmline-kotlin-plugin
  - compile-time only
  - no runtime artifact
  - no dependency on wasmline core at runtime
- wasmline-gradle-plugin
  - depends-on: wasmline-kotlin-plugin (applies plugin to consumer builds)
- wasmline-sample (all sub-apps)
  - depends-on: sample-common (shared contracts)
  - depends-on: wasmline (core runtime via Gradle plugin)

---

## Module Functional Mapping

- wasmline-core (C/C++ · Zig 0.16.0)
  - Engine.cpp: Wasmtime Engine singleton — global init / shutdown
  - Module.cpp: AOT and Pulley module compilation — keyed module cache
  - Session.cpp: per-invocation isolated linear memory region — execution context lifecycle
  - Api.cpp: JNI / C Interop surface — load, invoke, setOutbound, release

- wasmline (core Kotlin runtime)
  - commonMain
    - WasmlineService: contract marker interface
    - WasmlineGeneratedBridge: generated bridge base class
    - WasmlineEndpoint: endpoint abstraction for dispatch
    - HostDispatcher: inbound outbound call dispatch table
    - Payload: ByteArray wrapper with encoding metadata
  - hostMain
    - Wasmline: top-level host API singleton
    - WasmlineLoadState: sealed result — Success(wasmline) / Failure(cause)
    - WasmlineLoader: artifact loading entry point
    - link\<T\>(): IR rewrite target — returns typed outbound proxy
    - bind(impl): IR rewrite target — registers inbound handler
  - jniMain
    - Wasmline.jni.kt: @JvmStatic external JNI declarations delegating to wasmline-core
  - iosMain
    - Wasmline.ios.kt: Kotlin/Native C Interop wrappers delegating to wasmline-core
  - webMain
    - WebBindings.web.kt: expect/actual contract layer (no platform-specific interop)
    - WebWasmValue: i32/i64/f32/f64 sealed interface + WebWasmValueCodec
    - WebWasmRuntime: WebAssembly.compile/instantiate wrapper
    - WebWasmImportsBuilder: import object construction
    - WebArtifactFetcher: Fetch API-based artifact loader
    - WebWasmPlugin: WASI preview1 shims + bridge_inbound_/bridge_outbound_* handlers
    - WasmlineWeb: async prefetch API (bridges sync load model)
  - jsMain / wasmJsMain
    - WebBindings.js.kt / .wasmJs.kt: actual declarations via typed externals or JsAny
  - wasmWasiMain
    - WasmlineRouter: action registration and dispatch table
    - WasmBridge: plugin-side outbound call implementation
    - WasmlineServices.wasmWasi.kt: plugin-side bind() and link() entry points

- wasmline-loader
  - WasmlineLoader: public loadWasmline(artifactPath) API
  - Manifest parsing: .wlm Protobuf decoding
  - Signature verification: Ed25519 and ECDSA-P256
  - Artifact selection: .cwasm (target-specific) then .pwasm (portable fallback)

- wasmline-kotlin-plugin
  - WasmlineCompilerPluginRegistrar: Kotlin compiler plugin entry point
  - WasmlineCommandLineProcessor: plugin option parsing
  - WasmlineIrGenerationExtension: IR pass orchestrator
  - WasmlineServiceContractValidator: static constraint enforcement
  - WasmlineBridgeGenerator: *_WasmlineBridge IR class synthesis
  - WasmlineTypedEntryPointRewriter: link() and bind() call-site rewriting
  - WasmlineRuntimeSymbols: IrPluginContext symbol resolution
  - WasmlineIrDiagnostics: typed compiler diagnostic declarations
  - SignatureHash: SHA-256 action identifier derivation

- wasmline-cli
  - download: Wasmtime release binary download for target platforms
  - generate-key-pair: Ed25519 key pair generation
  - compile: .wasm to .cwasm per target triple and .pwasm Pulley image
  - manifest: Protobuf manifest generation with Ed25519 signature
  - build: full pipeline orchestration — compile then manifest then zip

- wasmline-gradle-plugin
  - Applies wasmline-kotlin-plugin as a Kotlin compiler plugin to consumer build configurations

---

- Bidirectional Data Flow Pipelines

- Inbound (host invokes plugin service)
  - Host calls module.link\<EchoService\>().echo("ping")
    - IR-synthesized proxy method on EchoService_WasmlineBridge
      - Serializes args via WasmlineSerializationFactory.encode
        - Produces ByteArray payload
      - Calls Wasmline.call(action, payload)
        - Native path
          - hostMain delegates to jniMain or iosMain actual
          - JNI/C Interop calls Api.cpp::invoke(action, payload)
            - Session.cpp: allocates isolated linear memory region
            - Writes action and payload into Wasm linear memory
            - Executes Wasmtime call — plugin entry point runs
              - WasmlineRouter.dispatch(action, payload) in plugin
                - Deserializes payload
                - Calls registered handler implementation
                - Serializes return value
              - Response ByteArray written to Wasm linear memory output buffer
            - Session reads response ByteArray across JNI boundary
        - Web path
          - BrowserWasmlineRuntime.call(action, payload)
            - Prefetched artifact cached by WebWasmArtifacts
            - WebWasmPlugin instantiated with binary
            - WASI imports: fd_write (stdout/stderr), random_get, clock_time_get
            - env.bridge_* imports: bridge_inbound_copy_params, bridge_inbound_set_response, bridge_outbound_call_host, bridge_outbound_get_response
            - __wasmline_wasi_entry export invoked with params pointer + payload pointer
            - Plugin dispatch runs synchronously
            - Response read back via bridge_inbound_set_response
            - No Base64 encoding needed (ByteArray passes directly through Kotlin layer)
      - Deserializes response via WasmlineSerializationFactory.decode
    - Returns typed result to host call site

- Outbound (plugin invokes host service)
  - Plugin calls wasmline.link\<HostNotificationService\>().notify("event")
    - IR-synthesized proxy on HostNotificationService_WasmlineBridge (plugin-side)
      - WasmBridge serializes args via WasmlineSerializationFactory.encode
      - Calls bridge_outbound_call_host(action, payload)
        - Native path
          - Wasmtime host function callback fires into JNI/C Interop host dispatcher
          - Api.cpp invokes registered HostDispatcher.dispatch(action, payload)
            - Host-side *_WasmlineBridge bound implementation handles call
            - Response serialized and returned to Api.cpp
          - Response written back into Wasm linear memory
        - Web path
          - env.bridge_outbound_call_host JS import fires
          - Kotlin host dispatch called synchronously from JS
          - Response written to shared buffer
          - env.bridge_outbound_get_response copies response to caller
      - Deserializes response in plugin
    - Returns typed result to plugin call site

---

## Compiler IR Transformation Pipeline

- WasmlineIrGenerationExtension.generate(moduleFragment, pluginContext)
  - Step 1: Discovery
    - Traverses moduleFragment.files
    - Collects IrClass where superTypes contains WasmlineService
  - Step 2: Validation (WasmlineServiceContractValidator)
    - Constraint: declaration must be interface — not abstract class, sealed type, or object
    - Constraint: all functions must be public
    - Constraint: suspend modifier is forbidden
    - Constraint: at most one regular value parameter per function
    - Constraint: no generic type parameters on the contract interface
    - Constraint: no overloaded method names within a single contract
    - Constraint: no vararg parameters
    - Constraint: no default parameter values
    - Constraint: no extension receivers
    - On violation: WasmlineIrDiagnostics error emitted — IR generation aborted
  - Step 3: Bridge synthesis (WasmlineBridgeGenerator)
    - For each validated contract interface
      - Generates internal class {Contract}_WasmlineBridge : WasmlineGeneratedBridge
      - Synthesizes val endpoint: WasmlineEndpoint field
      - Synthesizes var implementation: T? nullable field
      - For each interface method
        - Derives action: SignatureHash.hash(fullyQualifiedMethodSignature)
        - Generates invoke(action, payload): ByteArray dispatch branch
        - Generates bindAction(impl: T) registration logic
      - Injects generated IrClass into parent IrFile
  - Step 4: Call-site rewriting (WasmlineTypedEntryPointRewriter)
    - Visits all IrCall nodes in the module IR tree
    - Matches link\<T\>() call: replaces with {Contract}_WasmlineBridge(endpoint).asProxy()
    - Matches bind(impl) call: replaces with {Contract}_WasmlineBridge(endpoint).bind(impl)
    - Matches bind(contract, impl) call: resolves contract class argument then same rewrite

- SignatureHash
  - Input: fully-qualified class name + "#" + method name + "(" + parameter type descriptors + ")"
  - Algorithm: SHA-256
  - Output: lowercase hex string
  - Stability guarantee: invariant to package refactoring; coupled to method signature only

- IR test infrastructure
  - Box tests: `testData/box/*.kt` (6 files) with `fun box(): String` entry point
  - Diagnostic tests: `testData/diagnostics/*.kt` (2 files) with error markers
  - Generated: `test-gen/` (auto-generated test runners)
  - Snapshots: `*.fir.txt`, `*.fir.ir.txt` (auto-generated, never hand-edited)
  - Full documentation: [`docs/ir/index.md`](index.md)

---

## Artifact Lifecycle & Serialization Layer

- Plugin artifact lifecycle
  - Authoring
    - Source language: any WASI-targeting toolchain (Kotlin, Rust, C/C++, Go, AssemblyScript)
    - Output: raw .wasm binary
  - Compilation (wasmline-cli compile)
    - Input: raw .wasm
    - Wasmtime C-API Module::serialize: produces platform-specific .cwasm per target triple
    - Wasmtime Pulley backend: produces universal .pwasm bytecode image
    - Output per target: {name}-{arch}-{os}.cwasm and {name}-pulley64.pwasm
  - Manifest generation (wasmline-cli manifest)
    - Input: compiled artifact set
    - Protobuf encoding of manifest record
      - Fields: name, version, min-runtime-version, per-artifact SHA-256 checksums, key fingerprint
    - Ed25519 or ECDSA-P256 digital signature applied to encoded manifest
    - Output: manifest.wlm
  - Packaging (wasmline-cli build orchestration)
    - compile then manifest then zip into {name}-{version}.zip
  - Loading (wasmline-loader loadWasmline)
    - manifest.wlm deserialized
    - Signature verified against embedded public key — rejection on tamper
    - Artifact selected: .cwasm for current target triple then .pwasm fallback
    - Web: raw .wasm only — no manifest-based AOT loading
    - Output: WasmlineLoadState.Success(wasmline) or WasmlineLoadState.Failure(cause)

- Serialization layer
  - SPI interface: WasmlineSerializationFactory
    - id: String — factory identifier exchanged during module load negotiation
    - encode(value: T, descriptor): ByteArray
    - decode(bytes: ByteArray, descriptor): T
  - Built-in factories
    - WasmlineProtobufSerializationFactory
      - id: "protobuf"
      - codec: kotlinx.serialization Protobuf
    - WasmlineRawBytesSerializationFactory
      - id: "raw"
      - codec: identity pass-through for ByteArray parameters
  - Factory selection protocol
    - Host and plugin agree on factory id via WasmlineConfig at module load time
    - Factory id mismatch: runtime decode error on receiving side
  - Custom factory registration
    - WasmlineSerializationRegistry.register(factory) at process startup
    - Factory id is part of the binary protocol — must be stable across versions

- Web payload encoding (deprecated)
  - BrowserPayloadEncoding: ByteArray ↔ Base64 conversion (removed in Phase 2 refactor)
  - Replaced with direct ByteArray transmission through Kotlin layer
  - Fetch API-based artifact loading replaces synchronous XHR
