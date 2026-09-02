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
        RawValue.I32(21),
        RawValue.I32(1),
    ),
)
```

The Compose sample's **Raw Export** mode uses this same ABI. Build native
artifacts through `sample-raw-export-plugin`, which resolves the exact
backend-specific AOT profile and compiler asset:

```shell
cd ../../kotlin
./gradlew :sample-raw-export-plugin:wasmlineAssembleDebug
```

The artifact descriptor must preserve the `RAW_EXPORT` protocol,
`exportName = "add_i32"`, and the matching backend profile and target identity.
The Compose runner fills the current native runtime identity for direct
`.cwasm` or `.pwasm` paths.
