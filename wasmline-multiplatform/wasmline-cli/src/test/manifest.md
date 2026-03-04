# manifest

## generate manifest from compile output

```shell
./gradlew :wasmline-cli:run --args="manifest -d build/wasmline/output/manga-1.0.0 --key ed25519_private.key"
```

## generate manifest with full metadata

```shell
./gradlew :wasmline-cli:run --args="manifest -d build/wasmline/output/manga-1.2.0 --plugin-id crow.wasmline.manga --version 1.2.0 --version-code 120 --display-name 'Manga Reader' --author Crow --description 'A manga reader plugin' --key ed25519_private.key"
```

## generate manifest with hex string key

```shell
./gradlew :wasmline-cli:run --args="manifest -d build/wasmline/output/manga-1.0.0 --key 10829ee4b2894f74647aa109ff82ff549a176e28d64632b69f1c8d5a5225023b"
```

## expected input directory layout (produced by compile)

```
build/wasmline/output/manga-1.0.0/
├── manga-pulley64.pwasm
├── manga-aarch64-android.cwasm
├── ...
└── debug/
    └── compile-result.json    ← manifest reads this
```

## output

```
build/wasmline/output/manga-1.0.0/
├── manifest.wlm               ← generated
├── manga-pulley64.pwasm
├── manga-aarch64-android.cwasm
├── ...
└── debug/
    ├── compile-result.json
    └── manifest.json           ← generated
```

## options

| option           | required | default        | description                                                           |
|------------------|----------|----------------|-----------------------------------------------------------------------|
| `-d`, `--dir`    | yes      | -              | Directory containing compiled artifacts and debug/compile-result.json |
| `-k`, `--key`    | yes      | -              | Ed25519 private key: file path or hex string                          |
| `--plugin-id`    | no       | input filename | Plugin unique identifier                                              |
| `--version`      | no       | `1.0.0`        | Semantic version                                                      |
| `--version-code` | no       | `1`            | Integer version code                                                  |
| `--min-sdk`      | no       | CLI version    | Minimum wasmline SDK version                                          |
| `--display-name` | no       | -              | Plugin display name                                                   |
| `--author`       | no       | -              | Plugin author                                                         |
| `--description`  | no       | -              | Plugin description                                                    |
| `--icon-url`     | no       | -              | Icon URL or relative path                                             |
| `--home-url`     | no       | -              | Home page or repository URL                                           |
