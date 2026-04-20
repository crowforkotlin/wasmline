# download

## download latest wasmtime for current platform

```shell
./gradlew :wasmline-cli:run --args="download"
```

> On Apple Silicon, the CLI prefers the host macOS architecture even when Gradle runs on an x86_64 JDK under Rosetta. If you intentionally need the Intel build, pass `-a x86_64-macos`.

## download specific version

```shell
./gradlew :wasmline-cli:run --args="download -v v43.0.2"
```

## download multiple versions

```shell
./gradlew :wasmline-cli:run --args="download -v v43.0.2,v40.0.0"
```

## download for specific architecture

```shell
./gradlew :wasmline-cli:run --args="download -a aarch64-macos"
```

## download all architectures

```shell
./gradlew :wasmline-cli:run --args="download -a all"
```

## download to custom directory

```shell
./gradlew :wasmline-cli:run --args="download -o build/wasmline/wasmtime"
```

## force redownload

```shell
./gradlew :wasmline-cli:run --args="download -v v43.0.2 -f"
```

## output

```
build/wasmline/wasmtime/
└── wasmtime-v43.0.2-aarch64-macos/
    ├── wasmtime
    ├── ...
    └── .success
```

## options

| option             | required | default                   | description                                                        |
|--------------------|----------|---------------------------|--------------------------------------------------------------------|
| `-v`, `--versions` | no       | `latest`                  | Wasmtime versions to download (repeatable, comma-separated)        |
| `-a`, `--arch`     | no       | current platform          | Target architecture (e.g., `aarch64-macos`, `x86_64-linux`, `all`) |
| `-o`, `--output`   | no       | `build/wasmline/wasmtime` | Output directory                                                   |
| `-f`, `--force`    | no       | `false`                   | Force redownload even if already exists                            |
