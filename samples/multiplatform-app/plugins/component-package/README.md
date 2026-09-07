# External Component fixture package

This module packages a completed C, C++, or Rust `wasmline:service@1.0.0` Component.
It does not compile guest Kotlin. `componentInput` validates the copied raw
Component, then the Wasmline Gradle plugin creates the native `.pwasm` and
`.cwasm` artifacts and signs `manifest.wlm`.

```shell
cd samples/multiplatform-app
cp plugins/c-component-service/build/plugin.component.wasm plugins/component-package/input/plugin.component.wasm
./gradlew :sample-component-fixture:wasmlineAssembleDebug
```

The signed package is written to:

```text
plugins/component-package/build/wasmline/output/crow.wasmline.component.fixture-1.0.0/
```

Use `plugins/cpp-component-service/build/plugin.component.wasm` in the copy command to package the C++
fixture instead.

For Rust, copy the Component built in `plugins/rust-component-service` from
`target/wasm32-wasip2/debug/wasmline_service_rust_fixture.wasm` into the same
`input/plugin.component.wasm` destination before running the package task.
