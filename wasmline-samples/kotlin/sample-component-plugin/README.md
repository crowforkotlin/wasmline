# Kotlin Component RPC sample

This module demonstrates the fixed `wasmline:rpc@1.0.0` envelope:

- `sample.echo` decodes and encodes Kotlin Serialization Protobuf bytes;
- `sample.callback` calls the generated `Host.Import.invoke` binding and returns the host bytes;
- `sample.empty` returns an empty byte payload;
- `sample.trap` intentionally traps;
- unknown actions and codec mismatches return a WIT `rpc-error`.

The complete build is owned by the Wasmline Gradle plugin:

```shell
./gradlew :sample-component-plugin:wasmlineAssembleDebug
```

The task chain generates Kotlin bindings under `build/generated`, compiles the
Kotlin/Wasm WASI library with JDK 21, embeds WIT, creates and validates the
Component with the pinned `wit-bindgen` 0.57.1 and `wasm-tools` 1.255.0, then
uses the full Wasmtime CLI to produce matching `.pwasm` and `.cwasm` Component
artifacts. Generated bindings and intermediate Wasm files are build outputs and
are not committed. Configure `WASMTIME_COMPILER` or run
`./gradlew :sample-component-plugin:wasmlineDownloadWasmtimeCompiler` before
assembling when the compiler is not already available.

The generated bindings are imported through `impl.*`; this is why the guest
implementation lives in the `impl` package. The WIT world is the fixed
`wasmline:rpc@1.0.0` envelope, so the payload remains Wasmline codec bytes
inside the WIT `list<u8>` fields.
