# C Component RPC fixture

This fixture implements the canonical `wasmline:rpc@1.0.0` world without
copying or modifying its WIT source. CMake reads the world directly from
`wasmline-plugin-core`, and `wit-bindgen c` writes `plugin.c`, `plugin.h`, and
`plugin_component_type.o` under the local build directory.

The hand-written implementation exports `plugin_invoke` and calls the generated
`host_invoke` import for `sample.callback`. It never defines Wasmline's four
Core bridge imports. The `protobuf` payload is treated as opaque bytes:

- `sample.echo` returns a copied payload;
- `sample.callback` forwards the payload to `sample.host.callback`;
- `sample.empty` returns an empty payload;
- `sample.trap` emits a Wasm trap;
- unsupported codecs and actions return a WIT `rpc-error`.

## Build

Use the WASI SDK 33 CMake toolchain (the verified baseline), `wit-bindgen 0.57.1`, and
`wasm-tools 1.255.0`:

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
