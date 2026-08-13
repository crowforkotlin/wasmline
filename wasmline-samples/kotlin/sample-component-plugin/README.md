# Kotlin Wasmline Service sample

This module uses ordinary `WasmlineService`, `link`, and `bind` source code while the
Wasmline build plugin generates the fixed `wasmline:service@1.0.0` transport:

- `ComponentPluginService.echo` decodes and encodes Kotlin Serialization Protobuf bytes;
- `ComponentPluginService.callback` calls `link<ComponentHostService>()`;
- `ComponentPluginService.empty` returns an empty byte payload;
- `ComponentPluginService.trap` intentionally traps;
- unknown actions and codec mismatches return a WIT `service-error`.

The complete build is owned by the Wasmline Gradle plugin:

```shell
./gradlew :sample-component-plugin:wasmlineAssembleDebug
```

The task chain materializes Wasmline's canonical WIT, generates Kotlin bindings and the
Wasmline transport adapter under `build/generated`, compiles the
Kotlin/Wasm WASI library with JDK 21, embeds WIT, creates and validates the
Component with the pinned `wit-bindgen` 0.57.1 and `wasm-tools` 1.255.0, then
uses the full Wasmtime CLI to produce matching `.pwasm` and `.cwasm` Component
artifacts. Generated bindings and intermediate Wasm files are build outputs and
are not committed. The Gradle plugin downloads the pinned full Wasmtime CLI
automatically when the compiler is not already available.

The guest never imports generated `Host`/`Plugin` types directly and does not maintain an
action switch or Service error conversion. The fixed WIT `list<u8>` payload remains exactly the
bytes produced by the selected Wasmline serialization factory.
