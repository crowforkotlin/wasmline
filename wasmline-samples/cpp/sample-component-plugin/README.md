# C++ Component RPC fixture

This fixture implements the canonical `wasmline:rpc@1.0.0` world without
copying or modifying its WIT source. CMake reads the world directly from
`wasmline-plugin-core`, and `wit-bindgen cpp` writes `plugin.cpp`,
`plugin_cpp.h`, `wit.h`, and `plugin_component_type.o` under the local build
directory.

`wit-bindgen cpp` 0.57.1 emits one `std::expected::value()` call whose error
type owns move-only WIT values. libc++ correctly rejects that combination
because `value()` requires a copy-constructible error type. The CMake pipeline
applies a version-locked generated-source compatibility step that replaces
that call with the equivalent unchecked `operator*` after `has_value()`.
It fails closed if the pinned generator output changes; neither the generator
source nor generated files are edited by hand.

The hand-written implementation exports `exports::wasmline::rpc::Invoke` and
calls the generated `wasmline::rpc::Invoke` host import for `sample.callback`.
It never defines Wasmline's four Core bridge imports. The `protobuf` payload is
treated as opaque bytes:

- `sample.echo` returns the payload;
- `sample.callback` forwards the payload to `sample.host.callback`;
- `sample.empty` returns an empty payload;
- `sample.trap` emits a Wasm trap;
- unsupported codecs and actions return a WIT `rpc-error`.

## Build

The generated API uses `std::expected`, so use WASI SDK 33 (the verified C++23
baseline), plus `wit-bindgen 0.57.1` and `wasm-tools 1.255.0`:

```shell
cmake -S . -B build -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$WASI_SDK_PATH/share/cmake/wasi-sdk.cmake" \
  -DWIT_BINDGEN_EXECUTABLE=/path/to/wit-bindgen \
  -DWASM_TOOLS_EXECUTABLE=/path/to/wasm-tools
cmake --build build --target wasmline_component
```

The output is `build/plugin.component.wasm`. If the selected WASI SDK leaves
Preview 1 imports in the Core Wasm, also pass the pinned adapter:

```shell
-DWASI_PREVIEW1_ADAPTER=/path/to/wasi_snapshot_preview1.reactor.wasm
```

CMake rejects unpinned `wit-bindgen` and `wasm-tools` versions, validates the
Component, and prints its reconstructed WIT world. Generated bindings, object
files, Core Wasm, and Component Wasm remain ignored build outputs.
