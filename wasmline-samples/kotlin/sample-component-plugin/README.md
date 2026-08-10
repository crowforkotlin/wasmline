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
Component with the pinned `wit-bindgen` 0.57.1 and `wasm-tools` 1.255.0, and
packages the raw Component artifact. Generated bindings and intermediate Wasm
files are build outputs and are not committed. Component AOT is intentionally
not part of this sample yet.

The generated bindings are imported through `impl.*`; this is why the guest
implementation lives in the `impl` package. The WIT world is the fixed
`wasmline:rpc@1.0.0` envelope, so the payload remains Wasmline codec bytes
inside the WIT `list<u8>` fields.
