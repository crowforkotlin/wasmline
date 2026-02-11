# generate-key-pair

## generate key pair and print to console

```shell
./gradlew :wasmline-cli:run --args="generate-key-pair"
```

## generate key pair with specific algorithm

```shell
./gradlew :wasmline-cli:run --args="generate-key-pair -a Ed25519"
```

## generate key pair and save to files

```shell
./gradlew :wasmline-cli:run --args="generate-key-pair --save"
```

## generate key pair and save to custom directory

```shell
./gradlew :wasmline-cli:run --args="generate-key-pair --save -o build/wasmline/keys"
```

## output (console)

```
ALGORITHM: Ed25519
PUBLIC KEY: <64-char hex string>
PRIVATE KEY: <64-char hex string>
```

## output (with --save)

```
build/wasmline/keys/
├── ed25519_private.key
└── ed25519_public.key
```

## options

| option              | required | default                | description                                |
|---------------------|----------|------------------------|--------------------------------------------|
| `-a`, `--algorithm` | no       | `Ed25519`              | Signing algorithm to use                   |
| `-s`, `--save`      | no       | `false`                | Save keys to files in the output directory |
| `-o`, `--output`    | no       | `build/wasmline/keys`  | Output directory for key files             |
