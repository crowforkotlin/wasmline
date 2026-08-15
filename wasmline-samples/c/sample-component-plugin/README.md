# C Wasmline Service fixture

This fixture implements the canonical `wasmline:service@1.0.0` world without
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
- unsupported codecs and actions return a WIT `service-error`.

## Build

Use the WASI SDK 33 CMake toolchain (the verified baseline), `wit-bindgen 0.57.1`,
and `wasm-tools 1.256.0`:

```shell
export WASI_SDK_PATH=/path/to/wasi-sdk-33.0
bash ../configure.sh
bash ../build.sh
```

The build writes one raw Component:

- `../build/plugin.component.wasm`

Copy that file to `wasmline-samples/kotlin/sample-component-fixture/input/` and
run the Kotlin fixture package task to generate signed `.pwasm`, `.cwasm`, and
`manifest.wlm` outputs. If the selected WASI SDK leaves Preview 1 imports in the
Core Wasm, also pass the pinned adapter:

```shell
export WASI_PREVIEW1_ADAPTER=/path/to/wasi_snapshot_preview1.reactor.wasm
```

`../build.sh` runs configuration before building, so it cannot invoke CMake on
an unconfigured build directory. The scripts use Ninja by default; set
`CMAKE_GENERATOR` to use a different locally installed generator.

CMake rejects unpinned `wit-bindgen` and `wasm-tools` versions, validates the
Component, and prints its reconstructed WIT world. Generated bindings, object
files, Core Wasm, and Component Wasm remain ignored build outputs.
