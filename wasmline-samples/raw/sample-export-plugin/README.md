# Raw Export sample

This fixture is deliberately a plain Core Wasm module. It has no Wasmline
service bridge and no Component Model section.

The exported ABI is:

```text
add_i32(i32, i32) -> i32
```

Convert the WAT source to raw Wasm with `wat2wasm` or `wasm-tools parse`, then
load the resulting artifact with:

```kotlin
WasmlineLoader.load(
    WasmlineArtifactDescriptor(
        path = "sample-export.wasm",
        artifactFormat = WasmlineArtifactFormat.RAW_WASM,
        executionModel = WasmlineExecutionModel.CORE_WASM,
        invocationProtocol = WasmlineInvocationProtocol.RAW_EXPORT,
        exportName = "add_i32",
    ),
)
```

Invoke it with typed Core values:

```kotlin
module.invokeRawResult(
    exportName = "add_i32",
    arguments = listOf(
        WasmlineRawValue.I32(21),
        WasmlineRawValue.I32(1),
    ),
)
```

The Compose sample's **Raw Export** mode uses this same ABI. For a native
`.cwasm`, use the Wasmline compiler profile so the artifact matches the native
engine configuration:

```shell
wasm-tools parse plugin.wat -o sample-export.wasm
wasmtime compile sample-export.wasm -o sample-export.cwasm \
  -C collector=drc \
  -W gc=y -W function-references=y -W exceptions=y -W threads=n \
  -W simd=n -W relaxed-simd=n \
  -O static-memory-guard-size=0 -O dynamic-memory-guard-size=0 \
  -O signals-based-traps=n -O opt-level=2
```

The artifact descriptor must preserve the `RAW_EXPORT` protocol,
`exportName = "add_i32"`, and the matching Wasmtime/target metadata. The
Compose runner fills the current native target metadata for direct `.cwasm` or
`.pwasm` paths.
