#!/bin/bash

# =============================================================================
# update-md.sh — Regenerate all CLI documentation markdown files.
#
# When paths, file names, or product info change, update the variables below
# and re-run this script to refresh all md files at once.
#
# Usage:
#   chmod +x update-md.sh
#   ./update-md.sh
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ===== Configurable Variables =====

# Input wasm example location (edit DIR / FILE_NAME, then re-run this script)
WASM_INPUT_DIR="../wasmline-sample/plugin/build/compileSync/wasmWasi/main/productionLibrary/optimized"
WASM_INPUT_FILE_NAME="wasmline-multiplatform-wasmline-sample-plugin.wasm"
WASM_INPUT="${WASM_INPUT_DIR}/${WASM_INPUT_FILE_NAME}"

# Output artifact base name (controls -n, manifest dir, .cwasm/.pwasm/.zip filenames)
OUTPUT_NAME="wasmline-multiplatform-wasmline-sample-plugin"

# Default version (used by most examples)
VERSION="1.0.0"

# Alternative version for "custom version" examples
VERSION_ALT="1.2.0"
VERSION_CODE_ALT="120"

# Wasmtime release version / target arch (used by: download, build, compile examples)
WASMTIME_VERSION="v43.0.2"
WASMTIME_TARGET="aarch64-macos"
WASMTIME_DIR="build/wasmline/wasmtime/wasmtime-${WASMTIME_VERSION}-${WASMTIME_TARGET}"

# Backward-compatible alias used by the templates below
NAME="${OUTPUT_NAME}"

# Key file path (used by: build, manifest)
KEY_FILE="build/wasmline/keys/ed25519_private.key"

# Example hex key string (used by: build, manifest)
HEX_KEY="10829ee4b2894f74647aa109ff82ff549a176e28d64632b69f1c8d5a5225023b"

# Plugin metadata (used by: build, manifest)
PLUGIN_ID="crow.wasmline.${NAME}"
AUTHOR="Crow"
DISPLAY_NAME="Upgrade loader"
DESCRIPTION="A upgrade loader plugin"

# Output base directory
OUTPUT_DIR="build/wasmline/output"

# Key output directory (used by: keys)
KEYS_DIR="build/wasmline/keys"

# Gradle run prefix
GRADLE="./gradlew :wasmline-cli:run --args="

# ===== End Configurable Variables =====

echo "Generating documentation files in: ${SCRIPT_DIR}"

# ----- build.md -----
cat > "${SCRIPT_DIR}/build.md" << BUILDEOF
# build

## build with all defaults (full pipeline: compile → manifest → package)

\`\`\`shell
${GRADLE}"build -i ${WASM_INPUT} -wt ${WASMTIME_DIR} --key ${KEY_FILE}"
\`\`\`

## build with custom name and version

\`\`\`shell
${GRADLE}"build -i ${WASM_INPUT} -wt ${WASMTIME_DIR} -n ${NAME} -v ${VERSION_ALT} --key ${KEY_FILE}"
\`\`\`

## build with full manifest metadata

\`\`\`shell
${GRADLE}"build -i ${WASM_INPUT} -wt ${WASMTIME_DIR} -n ${NAME} --version ${VERSION_ALT} --version-code ${VERSION_CODE_ALT} --plugin-id ${PLUGIN_ID} --display-name '${DISPLAY_NAME}' --author ${AUTHOR} --description '${DESCRIPTION}' --key ${KEY_FILE}"
\`\`\`

## build with hex string key instead of file

\`\`\`shell
${GRADLE}"build -i ${WASM_INPUT} -wt ${WASMTIME_DIR} -n ${NAME} --version ${VERSION} --key ${HEX_KEY}"
\`\`\`

## build specific architectures only

\`\`\`shell
${GRADLE}"build -i ${WASM_INPUT} -wt ${WASMTIME_DIR} -n ${NAME} --version ${VERSION} -a pulley64 -a aarch64-android --key ${KEY_FILE}"
\`\`\`

## output

\`\`\`
build/wasmline/
├── output/
│   └── ${NAME}-${VERSION_ALT}/
│       ├── manifest.wlm
│       ├── ${NAME}-pulley64.pwasm
│       ├── ${NAME}-aarch64-android.cwasm
│       ├── ${NAME}-aarch64-linux.cwasm
│       ├── ${NAME}-aarch64-macos.cwasm
│       ├── ${NAME}-aarch64-ios.cwasm
│       ├── ${NAME}-x86_64-linux.cwasm
│       ├── ${NAME}-x86_64-windows.cwasm
│       └── debug/
│           ├── compile-result.json
│           └── manifest.json
└── dist/
    └── ${NAME}-${VERSION_ALT}.zip
\`\`\`

## options

| option              | required | default        | description                                  |
|---------------------|----------|----------------|----------------------------------------------|
| \`-i\`, \`--input\`     | yes      | -              | Input .wasm file path                        |
| \`-wt\`, \`--wasmtime\` | yes      | -              | Directory containing the wasmtime executable |
| \`-k\`, \`--key\`       | yes      | -              | Ed25519 private key: file path or hex string |
| \`-n\`, \`--name\`      | no       | input filename | Product name for output artifacts            |
| \`-v\`, \`--version\`         | no       | \`1.0.0\`        | Semantic version                             |
| \`--version-code\`    | no       | \`1\`            | Integer version code                         |
| \`--plugin-id\`       | no       | product name   | Plugin unique identifier                     |
| \`--min-sdk\`         | no       | CLI version    | Minimum wasmline SDK version                 |
| \`--display-name\`    | no       | -              | Plugin display name                          |
| \`--author\`          | no       | -              | Plugin author                                |
| \`--description\`     | no       | -              | Plugin description                           |
| \`--icon-url\`        | no       | -              | Icon URL or relative path                    |
| \`--home-url\`        | no       | -              | Home page or repository URL                  |
| \`-a\`, \`--arch\`      | no       | all targets    | Target architectures (repeatable)            |
BUILDEOF

echo "  build.md"

# ----- compile.md -----
cat > "${SCRIPT_DIR}/compile.md" << COMPILEEOF
# compile

## compile all default targets

\`\`\`shell
${GRADLE}"compile -i ${WASM_INPUT} -wt ${WASMTIME_DIR}"
\`\`\`

## compile with custom product name

\`\`\`shell
${GRADLE}"compile -i ${WASM_INPUT} -wt ${WASMTIME_DIR} -n ${NAME}"
\`\`\`

## compile with custom product name and version

\`\`\`shell
${GRADLE}"compile -i ${WASM_INPUT} -wt ${WASMTIME_DIR} -n ${NAME} --version ${VERSION_ALT}"
\`\`\`

## compile specific architectures only

\`\`\`shell
${GRADLE}"compile -i ${WASM_INPUT} -wt ${WASMTIME_DIR} -a pulley64 -a aarch64-android"
\`\`\`

## compile with custom output root directory

\`\`\`shell
${GRADLE}"compile -i ${WASM_INPUT} -wt ${WASMTIME_DIR} -o ${OUTPUT_DIR} -n ${NAME} --version ${VERSION}"
\`\`\`

## output

\`\`\`
${OUTPUT_DIR}/{name}-{version}/
├── {name}-pulley64.pwasm
├── {name}-aarch64-android.cwasm
├── {name}-aarch64-linux.cwasm
├── {name}-aarch64-macos.cwasm
├── {name}-aarch64-ios.cwasm
├── {name}-x86_64-linux.cwasm
├── {name}-x86_64-windows.cwasm
└── debug/
    └── compile-result.json
\`\`\`

## options

| option              | required | default                 | description                                  |
|---------------------|----------|-------------------------|----------------------------------------------|
| \`-i\`, \`--input\`     | yes      | -                       | Input .wasm file path                        |
| \`-wt\`, \`--wasmtime\` | yes      | -                       | Directory containing the wasmtime executable |
| \`-n\`, \`--name\`      | no       | input filename          | Product name for output artifacts            |
| \`-v\`, \`--version\`   | no       | \`1.0.0\`                 | Version string for output directory          |
| \`-o\`, \`--output\`    | no       | \`${OUTPUT_DIR}\` | Output root directory                        |
| \`-a\`, \`--arch\`      | no       | all targets             | Target architectures (repeatable)            |

## default targets

- \`pulley64\`
- \`x86_64-linux\`
- \`aarch64-linux\`
- \`aarch64-android\`
- \`aarch64-macos\`
- \`aarch64-ios\`
- \`x86_64-windows\`
COMPILEEOF

echo "  compile.md"

# ----- manifest.md -----
cat > "${SCRIPT_DIR}/manifest.md" << MANIFESTEOF
# manifest

## generate manifest from compile output

\`\`\`shell
${GRADLE}"manifest -d ${OUTPUT_DIR}/${NAME}-${VERSION} --key ${KEY_FILE}"
\`\`\`

## generate manifest with full metadata

\`\`\`shell
${GRADLE}"manifest -d ${OUTPUT_DIR}/${NAME}-${VERSION_ALT} --plugin-id ${PLUGIN_ID} --version ${VERSION_ALT} --version-code ${VERSION_CODE_ALT} --display-name '${DISPLAY_NAME}' --author ${AUTHOR} --description '${DESCRIPTION}' --key ${KEY_FILE}"
\`\`\`

## generate manifest with hex string key

\`\`\`shell
${GRADLE}"manifest -d ${OUTPUT_DIR}/${NAME}-${VERSION} --key ${HEX_KEY}"
\`\`\`

## expected input directory layout (produced by compile)

\`\`\`
${OUTPUT_DIR}/${NAME}-${VERSION}/
├── ${NAME}-pulley64.pwasm
├── ${NAME}-aarch64-android.cwasm
├── ...
└── debug/
    └── compile-result.json    ← manifest reads this
\`\`\`

## output

\`\`\`
${OUTPUT_DIR}/${NAME}-${VERSION}/
├── manifest.wlm               ← generated
├── ${NAME}-pulley64.pwasm
├── ${NAME}-aarch64-android.cwasm
├── ...
└── debug/
    ├── compile-result.json
    └── manifest.json           ← generated
\`\`\`

## options

| option           | required | default        | description                                                           |
|------------------|----------|----------------|-----------------------------------------------------------------------|
| \`-d\`, \`--dir\`    | yes      | -              | Directory containing compiled artifacts and debug/compile-result.json |
| \`-k\`, \`--key\`    | yes      | -              | Ed25519 private key: file path or hex string                          |
| \`--plugin-id\`    | no       | input filename | Plugin unique identifier                                              |
| \`--version\`      | no       | \`1.0.0\`        | Semantic version                                                      |
| \`--version-code\` | no       | \`1\`            | Integer version code                                                  |
| \`--min-sdk\`      | no       | CLI version    | Minimum wasmline SDK version                                          |
| \`--display-name\` | no       | -              | Plugin display name                                                   |
| \`--author\`       | no       | -              | Plugin author                                                         |
| \`--description\`  | no       | -              | Plugin description                                                    |
| \`--icon-url\`     | no       | -              | Icon URL or relative path                                             |
| \`--home-url\`     | no       | -              | Home page or repository URL                                           |
MANIFESTEOF

echo "  manifest.md"

# ----- download.md -----
cat > "${SCRIPT_DIR}/download.md" << DOWNLOADEOF
# download

## download latest wasmtime for current platform

\`\`\`shell
${GRADLE}"download"
\`\`\`

> On Apple Silicon, the CLI prefers the host macOS architecture even when Gradle runs on an x86_64 JDK under Rosetta. If you intentionally need the Intel build, pass \`-a x86_64-macos\`.

## download specific version

\`\`\`shell
${GRADLE}"download -v ${WASMTIME_VERSION}"
\`\`\`

## download multiple versions

\`\`\`shell
${GRADLE}"download -v ${WASMTIME_VERSION},v40.0.0"
\`\`\`

## download for specific architecture

\`\`\`shell
${GRADLE}"download -a aarch64-macos"
\`\`\`

## download all architectures

\`\`\`shell
${GRADLE}"download -a all"
\`\`\`

## download to custom directory

\`\`\`shell
${GRADLE}"download -o build/wasmline/wasmtime"
\`\`\`

## force redownload

\`\`\`shell
${GRADLE}"download -v ${WASMTIME_VERSION} -f"
\`\`\`

## output

\`\`\`
build/wasmline/wasmtime/
└── wasmtime-${WASMTIME_VERSION}-${WASMTIME_TARGET}/
    ├── wasmtime
    ├── ...
    └── .success
\`\`\`

## options

| option             | required | default                   | description                                                        |
|--------------------|----------|---------------------------|--------------------------------------------------------------------|
| \`-v\`, \`--versions\` | no       | \`latest\`                  | Wasmtime versions to download (repeatable, comma-separated)        |
| \`-a\`, \`--arch\`     | no       | current platform          | Target architecture (e.g., \`aarch64-macos\`, \`x86_64-linux\`, \`all\`) |
| \`-o\`, \`--output\`   | no       | \`build/wasmline/wasmtime\` | Output directory                                                   |
| \`-f\`, \`--force\`    | no       | \`false\`                   | Force redownload even if already exists                            |
DOWNLOADEOF

echo "  download.md"

# ----- keys.md -----
cat > "${SCRIPT_DIR}/keys.md" << KEYSEOF
# generate-key-pair

## generate key pair and print to console

\`\`\`shell
${GRADLE}"generate-key-pair"
\`\`\`

## generate key pair with specific algorithm

\`\`\`shell
${GRADLE}"generate-key-pair -a Ed25519"
\`\`\`

## generate key pair and save to files

\`\`\`shell
${GRADLE}"generate-key-pair --save"
\`\`\`

## generate key pair and save to custom directory

\`\`\`shell
${GRADLE}"generate-key-pair --save -o ${KEYS_DIR}"
\`\`\`

## output (console)

\`\`\`
ALGORITHM: Ed25519
PUBLIC KEY: <64-char hex string>
PRIVATE KEY: <64-char hex string>
\`\`\`

## output (with --save)

\`\`\`
${KEYS_DIR}/
├── ed25519_private.key
└── ed25519_public.key
\`\`\`

## options

| option              | required | default                | description                                |
|---------------------|----------|------------------------|--------------------------------------------|
| \`-a\`, \`--algorithm\` | no       | \`Ed25519\`              | Signing algorithm to use                   |
| \`-s\`, \`--save\`      | no       | \`false\`                | Save keys to files in the output directory |
| \`-o\`, \`--output\`    | no       | \`${KEYS_DIR}\`  | Output directory for key files             |
KEYSEOF

echo "  keys.md"

echo "Done. All 5 documentation files updated."
