# Kotlin Raw Export sample

This module demonstrates `CORE_WASM + RAW_EXPORT`. The guest exposes the numeric
Core Wasm function `add_i32(i32, i32) -> i32` with Kotlin's `@WasmExport`, and
the Wasmline Gradle plugin publishes matching signed CWASM/PWASM artifacts.

```shell
./gradlew :sample-raw-export-plugin:wasmlineAssembleDebug
```

The Desktop sample bundles this package automatically and invokes it through
`invokeRawResult`.
