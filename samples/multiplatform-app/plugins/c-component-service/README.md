# C Wasmline Service fixture

This fixture implements the canonical `wasmline:service@1.0.0` world without
copying or modifying its WIT source. `build.sh` reads the world directly from
`wasmline-plugin-core`, runs `wit-bindgen c` to write `plugin.c`, `plugin.h`, and
`plugin_component_type.o` under `build/generated`, then compiles and
Componentizes the result.

The hand-written implementation exports `plugin_invoke` and calls the generated
`host_invoke` import for `sample.callback`. It never defines Wasmline's four
Core bridge imports. The `protobuf` payload is treated as opaque bytes:

- `sample.echo` returns a copied payload;
- `sample.callback` forwards the payload to `sample.host.callback`;
- `sample.empty` returns an empty payload;
- `sample.trap` emits a Wasm trap;
- unsupported codecs and actions return a WIT `service-error`.

## Tools

Every Component tool is pinned to an exact release. `build.sh` accepts no other
version, and the pins stay in sync with `versions.json`.

| Tool | Version | Download | Required |
|---|---|---|---|
| WASI SDK | 33.0 | <https://github.com/WebAssembly/wasi-sdk/releases/tag/wasi-sdk-33> | yes, `WASI_SDK_PATH` |
| `wit-bindgen` | 0.57.1 | <https://github.com/bytecodealliance/wit-bindgen/releases/tag/v0.57.1> | yes |
| `wasm-tools` | 1.256.0 | <https://github.com/bytecodealliance/wasm-tools/releases/tag/v1.256.0> | yes |
| Preview 1 reactor adapter | 50.0.0 | <https://github.com/bytecodealliance/wasmtime/releases/tag/v50.0.0> | only when the guest imports `wasi_snapshot_preview1` |

This fixture uses the `c` and `cpp` generators, which upstream ships, so the
official `wit-bindgen` release works. Wasmline also publishes the same version
from `crowforkotlin/wit-bindgen`, which adds a `kotlin` generator; that fork is
interchangeable here when the reported version matches.

Download each archive manually, or let Wasmline fetch and verify it:

```shell
# Manual: x86_64 Linux, adjust the asset name for your platform.
curl -LO https://github.com/bytecodealliance/wit-bindgen/releases/download/v0.57.1/wit-bindgen-0.57.1-x86_64-linux.tar.gz
curl -LO https://github.com/bytecodealliance/wasm-tools/releases/download/v1.256.0/wasm-tools-1.256.0-x86_64-linux.tar.gz
curl -LO https://github.com/bytecodealliance/wasmtime/releases/download/v50.0.0/wasi_snapshot_preview1.reactor.wasm
tar xzf wit-bindgen-0.57.1-x86_64-linux.tar.gz
tar xzf wasm-tools-1.256.0-x86_64-linux.tar.gz
export WIT_BINDGEN_EXECUTABLE="$PWD/wit-bindgen-0.57.1-x86_64-linux/wit-bindgen"
export WASM_TOOLS_EXECUTABLE="$PWD/wasm-tools-1.256.0-x86_64-linux/wasm-tools"
export WASI_PREVIEW1_ADAPTER="$PWD/wasi_snapshot_preview1.reactor.wasm"
```

Expected SHA-256 for those exact assets:

| Asset | SHA-256 |
|---|---|
| `wit-bindgen-0.57.1-x86_64-linux.tar.gz` (upstream) | `ff95b2daf40fe4e83f30ebc7e12e919c9a96440f32bb87d4915e8ae5e98af2f3` |
| `wasm-tools-1.256.0-x86_64-linux.tar.gz` | `0b488be25c9e8a74dfa09f7eac614a927ea197f389d8fa5a21ffb1c15deb469d` |
| `wasi_snapshot_preview1.reactor.wasm` | `73b01f85e42e99c890efbbf619cd765e04e5809da36d976b87fc64abd87f5812` |

`wasm-tools` turns a compiled Core Wasm module into a Component. It embeds the
WIT world, resolves Component Model type sections, encodes the Component binary,
and validates the result. Wasmline also uses it to print the reconstructed WIT
world for inspection.

The preview 1 adapter is a small Component that implements `wasi_snapshot_preview1`
in terms of the Component Model. `wasm-tools component new --adapt` splices it in
so a guest that still imports Preview 1, for example through `printf` or
`iostream`, becomes a valid Component. Guests that import nothing from Preview 1
do not need it; `build.sh` inspects the Core Wasm and only passes `--adapt` when
the import is present.

### Where the tools come from

`build.sh` resolves each tool in this order and uses the first exact version match:

1. the `WIT_BINDGEN_EXECUTABLE`, `WASM_TOOLS_EXECUTABLE`, or `WASI_PREVIEW1_ADAPTER` override;
2. the same binary on `PATH`, if it reports the pinned version;
3. the Wasmline tool cache under `~/.wasmline/tools` (override with
   `WASMLINE_TOOL_CACHE`).

A `PATH` binary reporting a different version is skipped rather than rejected, so
an unrelated `wasm-tools` install does not break the build.

To populate the cache instead of downloading by hand, run these from the
repository root:

```shell
./gradlew :wasmline-cli:run --args="tools download --tool wit-bindgen"
./gradlew :wasmline-cli:run --args="tools download --tool wasm-tools"
./gradlew :wasmline-cli:run --args="tools download --tool adapter"
```

Those downloads are verified against the SHA-256 digests in
`wasmline-plugin-core/src/main/resources/META-INF/wasmline/toolchain/toolchain-lock.json`.
The Gradle task `wasmlineDownloadComponentTools`, registered by any project that
applies the Wasmline plugin, populates the same cache.

## Build

Use WASI SDK 33 (the verified baseline), `wit-bindgen 0.57.1`, and
`wasm-tools 1.262.0`:

```shell
export WASI_SDK_PATH=/path/to/wasi-sdk-33.0
bash ./build.sh
```

The script always rebuilds into `build/` and refuses any tool version other than
the pins above.

The build writes one raw Component:

- `build/plugin.component.wasm`

Copy that file to `samples/multiplatform-app/plugins/component-package/input/` and
run the Kotlin fixture package task to generate signed `.pwasm`, `.cwasm`, and
`manifest.wlm` outputs. Set `WASI_PREVIEW1_ADAPTER` only to override the adapter
that `build.sh` resolves on its own.

The script validates the Component and prints its reconstructed WIT world.
Generated bindings, object files, Core Wasm, and Component Wasm remain ignored
build outputs.
