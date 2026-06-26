#!/usr/bin/env bash

# ==============================================================================
# Generate wasmline-cli markdown documentation with configurable sample values
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

WASM_INPUT_DIR="../wasmline-sample/plugin/build/compileSync/wasmWasi/main/productionLibrary/optimized"
WASM_INPUT_FILE_NAME="wasmline-multiplatform-wasmline-sample-plugin.wasm"
OUTPUT_NAME="wasmline-multiplatform-wasmline-sample-plugin"
VERSION="1.0.0"
VERSION_ALT="1.0.0"
VERSION_CODE_ALT="1"
WASMTIME_VERSION="v45.0.6"
WASMTIME_TARGET="aarch64-macos"
WASMTIME_DIR=""
DOWNLOAD_EXTRA_VERSION="latest"
DOWNLOAD_ARCH="aarch64-macos"
SELECTED_ARCHES="pulley64,aarch64-linux-android"
KEY_FILE="build/wasmline/keys/ed25519_private.key"
HEX_KEY="10829ee4b2894f74647aa109ff82ff549a176e28d64632b69f1c8d5a5225023b"
PLUGIN_ID=""
AUTHOR="Crow"
DISPLAY_NAME="Upgrade loader"
DESCRIPTION="A upgrade loader plugin"
OUTPUT_DIR="build/wasmline/output"
KEYS_DIR="build/wasmline/keys"
KEY_ALGORITHM="Ed25519"
GRADLE="./gradlew :wasmline-cli:run --args="
SHOW_HELP=0
REPEATABLE_ARGS=()
WASMTIME_TARGET_EXPLICIT=0

SUPPORTED_DOWNLOAD_ARCHES=(
  "all"
  "aarch64-android"
  "aarch64-ios"
  "aarch64-ios-sim"
  "aarch64-linux"
  "aarch64-macos"
  "x86_64-linux"
  "x86_64-macos"
  "x86_64-windows"
)

trim() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

quote_token() {
  local value="$1"
  if [[ "$value" =~ ^[A-Za-z0-9_./,:=@+-]+$ ]]; then
    printf '%s' "$value"
  else
    printf "'%s'" "${value//\'/\'\\\'\'}"
  fi
}

escape_for_double_quotes() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  printf '%s' "$value"
}

render_inner_command() {
  local result=""
  local token
  for token in "$@"; do
    if [ -n "$result" ]; then
      result+=" "
    fi
    result+="$(quote_token "$token")"
  done
  printf '%s' "$result"
}

gradle_cmd() {
  local inner_command
  inner_command="$(render_inner_command "$@")"
  printf '%s"%s"' "$GRADLE" "$(escape_for_double_quotes "$inner_command")"
}

set_repeatable_args() {
  REPEATABLE_ARGS=()
  local option="$1"
  local csv="$2"
  local item trimmed_item

  IFS=',' read -r -a items <<<"$csv"
  for item in "${items[@]}"; do
    trimmed_item="$(trim "$item")"
    if [ -n "$trimmed_item" ]; then
      REPEATABLE_ARGS+=("$option" "$trimmed_item")
    fi
  done
}

require_value() {
  local option="$1"
  local value="${2:-}"
  if [ -z "$value" ]; then
    echo "Missing value for ${option}" >&2
    exit 1
  fi
}

extract_release_arch() {
  local value
  value="$(trim "$1")"
  value="${value##*/}"
  value="${value%.tar.xz}"
  value="${value%.zip}"
  value="${value%-c-api}"

  case "$value" in
  wasmtime-v*-*)
    printf '%s' "${value#wasmtime-v*-}"
    ;;
  *)
    printf '%s' "$value"
    ;;
  esac
}

is_supported_download_arch() {
  local expected="$1"
  local candidate
  for candidate in "${SUPPORTED_DOWNLOAD_ARCHES[@]}"; do
    if [ "$candidate" = "$expected" ]; then
      return 0
    fi
  done
  return 1
}

validate_download_arch() {
  local raw_value="$1"
  local normalized_arch="$2"

  if is_supported_download_arch "$normalized_arch"; then
    return 0
  fi

  echo "Invalid download architecture: ${raw_value}" >&2
  echo "Resolved architecture: ${normalized_arch}" >&2
  echo "Supported values: ${SUPPORTED_DOWNLOAD_ARCHES[*]}" >&2
  exit 1
}

apply_derived_defaults() {
  local raw_download_arch="$DOWNLOAD_ARCH"
  DOWNLOAD_ARCH="$(extract_release_arch "$DOWNLOAD_ARCH")"
  validate_download_arch "$raw_download_arch" "$DOWNLOAD_ARCH"

  WASM_INPUT="${WASM_INPUT_DIR%/}/${WASM_INPUT_FILE_NAME}"
  NAME="${OUTPUT_NAME}"

  if [ "$WASMTIME_TARGET_EXPLICIT" -eq 0 ] && [ "$DOWNLOAD_ARCH" != "all" ]; then
    WASMTIME_TARGET="$DOWNLOAD_ARCH"
  fi

  if [ -z "$WASMTIME_DIR" ]; then
    WASMTIME_DIR="build/wasmline/wasmtime/wasmtime-${WASMTIME_VERSION}-${WASMTIME_TARGET}"
  fi

  if [ -z "$PLUGIN_ID" ]; then
    PLUGIN_ID="crow.wasmline.${NAME}"
  fi
}

print_help() {
  cat <<EOF
Usage:
  ./cli.sh [options]

Regenerate wasmline-cli markdown docs with customizable sample values.

Options:
  --wasm-input-dir VALUE         Base directory of the sample wasm input
  --wasm-input-file-name VALUE   Sample wasm file name
  --output-name VALUE            Output artifact base name used in examples
  --version VALUE                Default version used by examples
  --version-alt VALUE            Alternate version used by custom examples
  --version-code-alt VALUE       Alternate version code used by custom examples
  --wasmtime-version VALUE       Wasmtime version shown in examples
  --wasmtime-target VALUE        Wasmtime target shown in examples, overrides auto-sync from --download-arch
  --wasmtime-dir VALUE           Wasmtime directory used by build/compile examples, defaults from version + target
  --download-extra-version VALUE Extra version used by download multi-version example
  --download-arch VALUE          Download architecture or release asset name, e.g. x86_64-linux or wasmtime-${WASMTIME_VERSION}-x86_64-linux.tar.xz
  --selected-arches VALUE        Comma-separated arch list for build/compile arch examples
  --key-file VALUE               Private key file path used by build/manifest examples
  --hex-key VALUE                Hex private key used by build/manifest examples
  --plugin-id VALUE              Plugin id used by build/manifest examples
  --author VALUE                 Plugin author used by examples
  --display-name VALUE           Plugin display name used by examples
  --description VALUE            Plugin description used by examples
  --output-dir VALUE             Output root directory used by compile/manifest examples
  --keys-dir VALUE               Output directory used by generate-key-pair examples
  --key-algorithm VALUE          Algorithm used by generate-key-pair example
  --gradle VALUE                 Gradle command prefix, default: ./gradlew :wasmline-cli:run --args=
  -h, --help                     Show this help and exit

Effective values:
  wasm-input-dir=${WASM_INPUT_DIR}
  wasm-input-file-name=${WASM_INPUT_FILE_NAME}
  output-name=${OUTPUT_NAME}
  version=${VERSION}
  version-alt=${VERSION_ALT}
  version-code-alt=${VERSION_CODE_ALT}
  wasmtime-version=${WASMTIME_VERSION}
  wasmtime-target=${WASMTIME_TARGET}
  wasmtime-dir=${WASMTIME_DIR}
  download-extra-version=${DOWNLOAD_EXTRA_VERSION}
  download-arch=${DOWNLOAD_ARCH}
  selected-arches=${SELECTED_ARCHES}
  key-file=${KEY_FILE}
  hex-key=${HEX_KEY}
  plugin-id=${PLUGIN_ID}
  author=${AUTHOR}
  display-name=${DISPLAY_NAME}
  description=${DESCRIPTION}
  output-dir=${OUTPUT_DIR}
  keys-dir=${KEYS_DIR}
  key-algorithm=${KEY_ALGORITHM}
  gradle=${GRADLE}

Sample:
  ./cli.sh \\
    --wasm-input-dir $(quote_token "$WASM_INPUT_DIR") \\
    --wasm-input-file-name $(quote_token "$WASM_INPUT_FILE_NAME") \\
    --output-name $(quote_token "$OUTPUT_NAME") \\
    --version $(quote_token "$VERSION") \\
    --version-alt $(quote_token "$VERSION_ALT") \\
    --version-code-alt $(quote_token "$VERSION_CODE_ALT") \\
    --wasmtime-version $(quote_token "$WASMTIME_VERSION") \\
    --wasmtime-target $(quote_token "$WASMTIME_TARGET") \\
    --wasmtime-dir $(quote_token "$WASMTIME_DIR") \\
    --download-extra-version $(quote_token "$DOWNLOAD_EXTRA_VERSION") \\
    --download-arch $(quote_token "$DOWNLOAD_ARCH") \\
    --selected-arches $(quote_token "$SELECTED_ARCHES") \\
    --key-file $(quote_token "$KEY_FILE") \\
    --hex-key $(quote_token "$HEX_KEY") \\
    --plugin-id $(quote_token "$PLUGIN_ID") \\
    --author $(quote_token "$AUTHOR") \\
    --display-name $(quote_token "$DISPLAY_NAME") \\
    --description $(quote_token "$DESCRIPTION") \\
    --output-dir $(quote_token "$OUTPUT_DIR") \\
    --keys-dir $(quote_token "$KEYS_DIR") \\
    --key-algorithm $(quote_token "$KEY_ALGORITHM") \\
    --gradle $(quote_token "$GRADLE")
EOF
}

parse_args() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
    --wasm-input-dir)
      require_value "$1" "${2:-}"
      WASM_INPUT_DIR="$2"
      shift 2
      ;;
    --wasm-input-file-name)
      require_value "$1" "${2:-}"
      WASM_INPUT_FILE_NAME="$2"
      shift 2
      ;;
    --output-name)
      require_value "$1" "${2:-}"
      OUTPUT_NAME="$2"
      shift 2
      ;;
    --version)
      require_value "$1" "${2:-}"
      VERSION="$2"
      shift 2
      ;;
    --version-alt)
      require_value "$1" "${2:-}"
      VERSION_ALT="$2"
      shift 2
      ;;
    --version-code-alt)
      require_value "$1" "${2:-}"
      VERSION_CODE_ALT="$2"
      shift 2
      ;;
    --wasmtime-version)
      require_value "$1" "${2:-}"
      WASMTIME_VERSION="$2"
      shift 2
      ;;
    --wasmtime-target)
      require_value "$1" "${2:-}"
      WASMTIME_TARGET="$2"
      WASMTIME_TARGET_EXPLICIT=1
      shift 2
      ;;
    --wasmtime-dir)
      require_value "$1" "${2:-}"
      WASMTIME_DIR="$2"
      shift 2
      ;;
    --download-extra-version)
      require_value "$1" "${2:-}"
      DOWNLOAD_EXTRA_VERSION="$2"
      shift 2
      ;;
    --download-arch)
      require_value "$1" "${2:-}"
      DOWNLOAD_ARCH="$2"
      shift 2
      ;;
    --selected-arches)
      require_value "$1" "${2:-}"
      SELECTED_ARCHES="$2"
      shift 2
      ;;
    --key-file)
      require_value "$1" "${2:-}"
      KEY_FILE="$2"
      shift 2
      ;;
    --hex-key)
      require_value "$1" "${2:-}"
      HEX_KEY="$2"
      shift 2
      ;;
    --plugin-id)
      require_value "$1" "${2:-}"
      PLUGIN_ID="$2"
      shift 2
      ;;
    --author)
      require_value "$1" "${2:-}"
      AUTHOR="$2"
      shift 2
      ;;
    --display-name)
      require_value "$1" "${2:-}"
      DISPLAY_NAME="$2"
      shift 2
      ;;
    --description)
      require_value "$1" "${2:-}"
      DESCRIPTION="$2"
      shift 2
      ;;
    --output-dir)
      require_value "$1" "${2:-}"
      OUTPUT_DIR="$2"
      shift 2
      ;;
    --keys-dir)
      require_value "$1" "${2:-}"
      KEYS_DIR="$2"
      shift 2
      ;;
    --key-algorithm)
      require_value "$1" "${2:-}"
      KEY_ALGORITHM="$2"
      shift 2
      ;;
    --gradle)
      require_value "$1" "${2:-}"
      GRADLE="$2"
      shift 2
      ;;
    -h | --help)
      SHOW_HELP=1
      shift
      ;;
    *)
      echo "Unknown option: $1" >&2
      echo "Run ./cli.sh --help for usage." >&2
      exit 1
      ;;
    esac
  done
}

parse_args "$@"
apply_derived_defaults

if [ "$SHOW_HELP" -eq 1 ]; then
  print_help
  exit 0
fi

set_repeatable_args "-a" "$SELECTED_ARCHES"
SELECTED_ARCH_ARGS=("${REPEATABLE_ARGS[@]}")

echo "Generating documentation files in: ${SCRIPT_DIR}"

cat >"${SCRIPT_DIR}/build.md" <<BUILDEOF
# build

## build with all defaults (full pipeline: compile → manifest → package)

\`\`\`shell
$(gradle_cmd build -i "$WASM_INPUT" -wt "$WASMTIME_DIR" --key "$KEY_FILE")
\`\`\`

## build with custom name and version

\`\`\`shell
$(gradle_cmd build -i "$WASM_INPUT" -wt "$WASMTIME_DIR" -n "$NAME" -v "$VERSION_ALT" --key "$KEY_FILE")
\`\`\`

## build with full manifest metadata

\`\`\`shell
$(gradle_cmd build -i "$WASM_INPUT" -wt "$WASMTIME_DIR" -n "$NAME" --version "$VERSION_ALT" --version-code "$VERSION_CODE_ALT" --plugin-id "$PLUGIN_ID" --display-name "$DISPLAY_NAME" --author "$AUTHOR" --description "$DESCRIPTION" --key "$KEY_FILE")
\`\`\`

## build with hex string key instead of file

\`\`\`shell
$(gradle_cmd build -i "$WASM_INPUT" -wt "$WASMTIME_DIR" -n "$NAME" --version "$VERSION" --key "$HEX_KEY")
\`\`\`

## build specific architectures only

\`\`\`shell
$(gradle_cmd build -i "$WASM_INPUT" -wt "$WASMTIME_DIR" -n "$NAME" --version "$VERSION" "${SELECTED_ARCH_ARGS[@]}" --key "$KEY_FILE")
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
| \`-wt\`, \`--wasmtime\` | yes      | -              | Directory containing the wasmtime-min executable |
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

cat >"${SCRIPT_DIR}/compile.md" <<COMPILEEOF
# compile

## compile all default targets

\`\`\`shell
$(gradle_cmd compile -i "$WASM_INPUT" -wt "$WASMTIME_DIR")
\`\`\`

## compile with custom product name

\`\`\`shell
$(gradle_cmd compile -i "$WASM_INPUT" -wt "$WASMTIME_DIR" -n "$NAME")
\`\`\`

## compile with custom product name and version

\`\`\`shell
$(gradle_cmd compile -i "$WASM_INPUT" -wt "$WASMTIME_DIR" -n "$NAME" --version "$VERSION_ALT")
\`\`\`

## compile specific architectures only

\`\`\`shell
$(gradle_cmd compile -i "$WASM_INPUT" -wt "$WASMTIME_DIR" "${SELECTED_ARCH_ARGS[@]}")
\`\`\`

## compile with custom output root directory

\`\`\`shell
$(gradle_cmd compile -i "$WASM_INPUT" -wt "$WASMTIME_DIR" -o "$OUTPUT_DIR" -n "$NAME" --version "$VERSION")
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
| \`-wt\`, \`--wasmtime\` | yes      | -                       | Directory containing the wasmtime-min executable |
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

cat >"${SCRIPT_DIR}/manifest.md" <<MANIFESTEOF
# manifest

## generate manifest from compile output

\`\`\`shell
$(gradle_cmd manifest -d "${OUTPUT_DIR}/${NAME}-${VERSION}" --key "$KEY_FILE")
\`\`\`

## generate manifest with full metadata

\`\`\`shell
$(gradle_cmd manifest -d "${OUTPUT_DIR}/${NAME}-${VERSION_ALT}" --plugin-id "$PLUGIN_ID" --version "$VERSION_ALT" --version-code "$VERSION_CODE_ALT" --display-name "$DISPLAY_NAME" --author "$AUTHOR" --description "$DESCRIPTION" --key "$KEY_FILE")
\`\`\`

## generate manifest with hex string key

\`\`\`shell
$(gradle_cmd manifest -d "${OUTPUT_DIR}/${NAME}-${VERSION}" --key "$HEX_KEY")
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

cat >"${SCRIPT_DIR}/download.md" <<DOWNLOADEOF
# download

## download latest wasmtime-min for current platform

\`\`\`shell
$(gradle_cmd download)
\`\`\`

> On Apple Silicon, the CLI prefers the host macOS architecture even when Gradle runs on an x86_64 JDK under Rosetta. If you intentionally need the Intel build, pass \`-a x86_64-macos\`.

## download specific version

\`\`\`shell
$(gradle_cmd download -v "$WASMTIME_VERSION")
\`\`\`

## download multiple versions

\`\`\`shell
$(gradle_cmd download -v "${WASMTIME_VERSION},${DOWNLOAD_EXTRA_VERSION}")
\`\`\`

## download for specific architecture

\`\`\`shell
$(gradle_cmd download -a "$DOWNLOAD_ARCH")
\`\`\`

## download all architectures

\`\`\`shell
$(gradle_cmd download -a all)
\`\`\`

## download to custom directory

\`\`\`shell
$(gradle_cmd download -o build/wasmline/wasmtime)
\`\`\`

## force redownload

\`\`\`shell
$(gradle_cmd download -v "$WASMTIME_VERSION" -f)
\`\`\`

## output

\`\`\`
build/wasmline/wasmtime/
└── wasmtime-${WASMTIME_VERSION}-${WASMTIME_TARGET}/
  ├── wasmtime-min
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

cat >"${SCRIPT_DIR}/keys.md" <<KEYSEOF
# generate-key-pair

## generate key pair and print to console

\`\`\`shell
$(gradle_cmd generate-key-pair)
\`\`\`

## generate key pair with specific algorithm

\`\`\`shell
$(gradle_cmd generate-key-pair -a "$KEY_ALGORITHM")
\`\`\`

## generate key pair and save to files

\`\`\`shell
$(gradle_cmd generate-key-pair --save)
\`\`\`

## generate key pair and save to custom directory

\`\`\`shell
$(gradle_cmd generate-key-pair --save -o "$KEYS_DIR")
\`\`\`

## output (console)

\`\`\`
ALGORITHM: ${KEY_ALGORITHM}
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
| \`-a\`, \`--algorithm\` | no       | \`${KEY_ALGORITHM}\`              | Signing algorithm to use                   |
| \`-s\`, \`--save\`      | no       | \`false\`                | Save keys to files in the output directory |
| \`-o\`, \`--output\`    | no       | \`${KEYS_DIR}\`  | Output directory for key files             |
KEYSEOF

echo "  keys.md"
echo "Done. All 5 documentation files updated."
