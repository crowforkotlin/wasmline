# External Component fixture package

This module packages a completed C or C++ `wasmline:service@1.0.0` Component.
It does not compile guest Kotlin. `componentInput` validates the copied raw
Component, then the Wasmline Gradle plugin creates the native `.pwasm` and
`.cwasm` artifacts and signs `manifest.wlm`.

```shell
cd wasmline-samples/kotlin
cp ../c/build/plugin.component.wasm sample-component-fixture/input/plugin.component.wasm
./gradlew :sample-component-fixture:wasmlineAssembleDebug
```

The signed package is written to:

```text
sample-component-fixture/build/wasmline/output/crow.wasmline.component.fixture-1.0.0/
```

Use `../cpp/build/plugin.component.wasm` in the copy command to package the C++
fixture instead.
