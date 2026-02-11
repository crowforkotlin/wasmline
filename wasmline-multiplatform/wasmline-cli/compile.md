# compile

## compile all default targets

```shell
./gradlew :wasmline-cli:run --args="compile -i ../wasmline-sample/plugin/build/compileSync/wasmWasi/main/productionLibrary/optimized/plugin.wasm -wt build/wasmline/wasmtime/wasmtime-v41.0.1-aarch64-macos"
```

## compile with custom product name

```shell
./gradlew :wasmline-cli:run --args="compile -i ../wasmline-sample/plugin/build/compileSync/wasmWasi/main/productionLibrary/optimized/plugin.wasm -wt build/wasmline/wasmtime/wasmtime-v41.0.1-aarch64-macos -n plugin"
```

## compile with custom product name and version

```shell
./gradlew :wasmline-cli:run --args="compile -i ../wasmline-sample/plugin/build/compileSync/wasmWasi/main/productionLibrary/optimized/plugin.wasm -wt build/wasmline/wasmtime/wasmtime-v41.0.1-aarch64-macos -n plugin --version 1.2.0"
```

## compile specific architectures only

```shell
./gradlew :wasmline-cli:run --args="compile -i ../wasmline-sample/plugin/build/compileSync/wasmWasi/main/productionLibrary/optimized/plugin.wasm -wt build/wasmline/wasmtime/wasmtime-v41.0.1-aarch64-macos -a pulley64 -a aarch64-android"
```

## compile with custom output root directory

```shell
./gradlew :wasmline-cli:run --args="compile -i ../wasmline-sample/plugin/build/compileSync/wasmWasi/main/productionLibrary/optimized/plugin.wasm -wt build/wasmline/wasmtime/wasmtime-v41.0.1-aarch64-macos -o build/wasmline/output -n plugin --version 1.0.0"
```

## output

```
build/wasmline/output/{name}-{version}/
├── {name}-pulley64.pwasm
├── {name}-aarch64-android.cwasm
├── {name}-aarch64-linux.cwasm
├── {name}-aarch64-macos.cwasm
├── {name}-aarch64-ios.cwasm
├── {name}-x86_64-linux.cwasm
├── {name}-x86_64-windows.cwasm
└── debug/
    └── compile-result.json
```

## options

| option              | required | default                 | description                                  |
|---------------------|----------|-------------------------|----------------------------------------------|
| `-i`, `--input`     | yes      | -                       | Input .wasm file path                        |
| `-wt`, `--wasmtime` | yes      | -                       | Directory containing the wasmtime executable |
| `-n`, `--name`      | no       | input filename          | Product name for output artifacts            |
| `-v`, `--version`   | no       | `1.0.0`                 | Version string for output directory          |
| `-o`, `--output`    | no       | `build/wasmline/output` | Output root directory                        |
| `-a`, `--arch`      | no       | all targets             | Target architectures (repeatable)            |

## default targets

- `pulley64`
- `x86_64-linux`
- `aarch64-linux`
- `aarch64-android`
- `aarch64-macos`
- `aarch64-ios`
- `x86_64-windows`
