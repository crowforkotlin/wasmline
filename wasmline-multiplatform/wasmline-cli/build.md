# build

## build with all defaults (full pipeline: compile → manifest → package)

```shell
./gradlew :wasmline-cli:run --args="build -i ../wasmline-sample/plugin/build/compileSync/wasmWasi/main/productionLibrary/optimized/wasmline-multiplatform-wasmline-sample-plugin.wasm -wt build/wasmline/wasmtime/wasmtime-v43.0.2-aarch64-macos --key build/wasmline/keys/ed25519_private.key"
```

## build with custom name and version

```shell
./gradlew :wasmline-cli:run --args="build -i ../wasmline-sample/plugin/build/compileSync/wasmWasi/main/productionLibrary/optimized/wasmline-multiplatform-wasmline-sample-plugin.wasm -wt build/wasmline/wasmtime/wasmtime-v43.0.2-aarch64-macos -n wasmline-multiplatform-wasmline-sample-plugin -v 1.2.0 --key build/wasmline/keys/ed25519_private.key"
```

## build with full manifest metadata

```shell
./gradlew :wasmline-cli:run --args="build -i ../wasmline-sample/plugin/build/compileSync/wasmWasi/main/productionLibrary/optimized/wasmline-multiplatform-wasmline-sample-plugin.wasm -wt build/wasmline/wasmtime/wasmtime-v43.0.2-aarch64-macos -n wasmline-multiplatform-wasmline-sample-plugin --version 1.2.0 --version-code 120 --plugin-id crow.wasmline.wasmline-multiplatform-wasmline-sample-plugin --display-name 'Upgrade loader' --author Crow --description 'A upgrade loader plugin' --key build/wasmline/keys/ed25519_private.key"
```

## build with hex string key instead of file

```shell
./gradlew :wasmline-cli:run --args="build -i ../wasmline-sample/plugin/build/compileSync/wasmWasi/main/productionLibrary/optimized/wasmline-multiplatform-wasmline-sample-plugin.wasm -wt build/wasmline/wasmtime/wasmtime-v43.0.2-aarch64-macos -n wasmline-multiplatform-wasmline-sample-plugin --version 1.0.0 --key 10829ee4b2894f74647aa109ff82ff549a176e28d64632b69f1c8d5a5225023b"
```

## build specific architectures only

```shell
./gradlew :wasmline-cli:run --args="build -i ../wasmline-sample/plugin/build/compileSync/wasmWasi/main/productionLibrary/optimized/wasmline-multiplatform-wasmline-sample-plugin.wasm -wt build/wasmline/wasmtime/wasmtime-v43.0.2-aarch64-macos -n wasmline-multiplatform-wasmline-sample-plugin --version 1.0.0 -a pulley64 -a aarch64-android --key build/wasmline/keys/ed25519_private.key"
```

## output

```
build/wasmline/
├── output/
│   └── wasmline-multiplatform-wasmline-sample-plugin-1.2.0/
│       ├── manifest.wlm
│       ├── wasmline-multiplatform-wasmline-sample-plugin-pulley64.pwasm
│       ├── wasmline-multiplatform-wasmline-sample-plugin-aarch64-android.cwasm
│       ├── wasmline-multiplatform-wasmline-sample-plugin-aarch64-linux.cwasm
│       ├── wasmline-multiplatform-wasmline-sample-plugin-aarch64-macos.cwasm
│       ├── wasmline-multiplatform-wasmline-sample-plugin-aarch64-ios.cwasm
│       ├── wasmline-multiplatform-wasmline-sample-plugin-x86_64-linux.cwasm
│       ├── wasmline-multiplatform-wasmline-sample-plugin-x86_64-windows.cwasm
│       └── debug/
│           ├── compile-result.json
│           └── manifest.json
└── dist/
    └── wasmline-multiplatform-wasmline-sample-plugin-1.2.0.zip
```

## options

| option              | required | default        | description                                  |
|---------------------|----------|----------------|----------------------------------------------|
| `-i`, `--input`     | yes      | -              | Input .wasm file path                        |
| `-wt`, `--wasmtime` | yes      | -              | Directory containing the wasmtime executable |
| `-k`, `--key`       | yes      | -              | Ed25519 private key: file path or hex string |
| `-n`, `--name`      | no       | input filename | Product name for output artifacts            |
| `-v`, `--version`         | no       | `1.0.0`        | Semantic version                             |
| `--version-code`    | no       | `1`            | Integer version code                         |
| `--plugin-id`       | no       | product name   | Plugin unique identifier                     |
| `--min-sdk`         | no       | CLI version    | Minimum wasmline SDK version                 |
| `--display-name`    | no       | -              | Plugin display name                          |
| `--author`          | no       | -              | Plugin author                                |
| `--description`     | no       | -              | Plugin description                           |
| `--icon-url`        | no       | -              | Icon URL or relative path                    |
| `--home-url`        | no       | -              | Home page or repository URL                  |
| `-a`, `--arch`      | no       | all targets    | Target architectures (repeatable)            |
